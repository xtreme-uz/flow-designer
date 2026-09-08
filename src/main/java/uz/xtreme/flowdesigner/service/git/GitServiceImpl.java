package uz.xtreme.flowdesigner.service.git;

import uz.xtreme.flowdesigner.config.GitProperties;
import uz.xtreme.flowdesigner.exception.GitAuthenticationException;
import uz.xtreme.flowdesigner.exception.GitOperationException;
import uz.xtreme.flowdesigner.exception.GitSyncConflictException;
import uz.xtreme.flowdesigner.exception.GitVersionConflictException;
import uz.xtreme.flowdesigner.exception.WorkspaceNotFoundException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Implementation of GitService with per-user workspaces and optimistic locking.
 */
@Service
public class GitServiceImpl implements GitService {

    private static final Logger log = LoggerFactory.getLogger(GitServiceImpl.class);

    private static final String GIT_DIR = ".git";
    private static final String WORKSPACE_METADATA_FILE = "flowdesigner-workspace.properties";
    private static final String THUB_DIR = "THUB";
    private static final String LAYOUT_DIR = ".flowdesigner";
    /**
     * The directories this application owns. Everything under them is staged on
     * commit and counts towards the workspace being clean; anything else in the
     * clone is someone else's file, reported separately rather than committed.
     */
    static final List<String> MANAGED_DIRS = List.of(THUB_DIR, LAYOUT_DIR);
    private static final int FETCH_TIMEOUT_SECONDS = 10;
    private static final Duration FETCH_INTERVAL = Duration.ofSeconds(30);

    private final GitProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkspaceInfo> workspaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Git> gitInstances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastFetchAt = new ConcurrentHashMap<>();
    /** One clone, one index: two pulls at once collide on .git/index.lock. */
    private final ReentrantLock mainRepoLock = new ReentrantLock();

    private final UserGitCredentials userGitCredentials;

    private Git mainRepo;
    private CredentialsProvider credentialsProvider;

    @Autowired
    public GitServiceImpl(GitProperties properties, UserGitCredentials userGitCredentials) {
        this.properties = properties;
        this.userGitCredentials = userGitCredentials;
    }

    /**
     * Uses the configured service credentials for everything — the shape the
     * tests exercise, and the behaviour when no user token is available.
     */
    public GitServiceImpl(GitProperties properties) {
        this(properties, UserGitCredentials.disabled());
    }

    /**
     * Credentials for an operation made on behalf of a user: their own token when
     * one is available, the service account otherwise. Repository-wide work
     * (cloning and refreshing the main repo) always uses the service account —
     * it runs on a schedule, with no user in context.
     */
    private CredentialsProvider userOrServiceCredentials() {
        return userGitCredentials.forCurrentUser().orElse(credentialsProvider);
    }

    @PostConstruct
    public void init() {
        log.info("Initializing GitService with remote URL: {}", properties.remoteUrl());
        initAuthentication();
        initMainRepo();
        restoreWorkspaces();
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down GitService");
        gitInstances.values().forEach(Git::close);
        gitInstances.clear();
        if (mainRepo != null) {
            mainRepo.close();
        }
    }

    private void initAuthentication() {
        GitProperties.Credentials creds = properties.credentials();

        if (creds.isHttpAuth()) {
            log.info("Using HTTP authentication");
            credentialsProvider = new UsernamePasswordCredentialsProvider(
                    creds.username(), creds.password());
        } else if (creds.isSshAuth()) {
            log.info("Using SSH authentication with key: {}", creds.sshKeyPath());
            File sshDir = new File(creds.sshKeyPath()).getParentFile();
            SshSessionFactory sshSessionFactory = new SshdSessionFactoryBuilder()
                    .setPreferredAuthentications("publickey")
                    .setHomeDirectory(FS.DETECTED.userHome())
                    .setSshDirectory(sshDir)
                    .build(null);
            SshSessionFactory.setInstance(sshSessionFactory);
        } else {
            log.info("No Git credentials configured, using default authentication");
        }
    }

    @Override
    public void initMainRepo() {
        Path mainPath = Path.of(properties.mainRepoPath());

        try {
            Files.createDirectories(mainPath);

            if (Files.exists(mainPath.resolve(GIT_DIR))) {
                log.info("Opening existing main repository at {}", mainPath);
                mainRepo = Git.open(mainPath.toFile());
                // Verify branch matches configured default, re-clone if mismatched
                String currentBranch = mainRepo.getRepository().getBranch();
                if (currentBranch != null && !currentBranch.equals(properties.defaultBranch())) {
                    log.warn("Main repo on branch '{}' but expected '{}', re-cloning", currentBranch, properties.defaultBranch());
                    mainRepo.close();
                    mainRepo = null;
                    deleteDirectory(mainPath);
                }
                if (mainRepo != null) {
                    pullMainRepo();
                }
            }
            if (mainRepo == null && properties.remoteUrl() != null && !properties.remoteUrl().isBlank()) {
                log.info("Cloning main repository from {} to {}", properties.remoteUrl(), mainPath);
                var cloneCommand = Git.cloneRepository()
                        .setURI(properties.remoteUrl())
                        .setDirectory(mainPath.toFile())
                        .setBranch(properties.defaultBranch());

                if (credentialsProvider != null) {
                    cloneCommand.setCredentialsProvider(credentialsProvider);
                }

                mainRepo = cloneCommand.call();
                log.info("Main repository cloned successfully");
            } else {
                log.warn("No remote URL configured and no existing repo found at {}", mainPath);
            }
        } catch (TransportException e) {
            throw new GitAuthenticationException("Failed to authenticate with Git remote", e);
        } catch (GitAPIException | IOException e) {
            throw new GitOperationException("Failed to initialize main repository", e);
        }
    }

    @Override
    public void pullMainRepo() {
        if (mainRepo == null) {
            log.warn("Main repository not initialized, skipping pull");
            return;
        }

        // Requests pull the main repo before every read, and a scheduled refresh
        // does the same; without this they can run at the same time
        mainRepoLock.lock();
        try {
            // Check if repo has any commits - skip pull for empty repos
            ObjectId head = mainRepo.getRepository().resolve("HEAD");
            if (head == null) {
                log.debug("Main repository is empty (no commits), skipping pull");
                return;
            }

            log.debug("Pulling latest changes to main repository");
            var pullCommand = mainRepo.pull()
                    .setRemoteBranchName(properties.defaultBranch());
            if (credentialsProvider != null) {
                pullCommand.setCredentialsProvider(credentialsProvider);
            }
            PullResult result = pullCommand.call();
            if (!result.isSuccessful()) {
                // Nothing writes to the main repo, so this means it was tampered with locally
                log.warn("Main repository pull did not complete cleanly: {}", result);
            } else {
                log.debug("Main repository updated successfully");
            }
        } catch (TransportException e) {
            throw new GitAuthenticationException("Failed to pull main repository", e);
        } catch (GitAPIException | IOException e) {
            throw new GitOperationException("Failed to pull main repository", e);
        } finally {
            mainRepoLock.unlock();
        }
    }

    private void restoreWorkspaces() {
        Path workspacesPath = Path.of(properties.workspacesPath());
        if (!Files.exists(workspacesPath)) {
            return;
        }

        log.info("Restoring existing workspaces from {}", workspacesPath);
        try (Stream<Path> paths = Files.list(workspacesPath)) {
            paths.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve(GIT_DIR)))
                    .forEach(this::restoreWorkspace);
        } catch (IOException e) {
            log.warn("Failed to restore workspaces", e);
        }
    }

    /**
     * Rebuilds the in-memory workspace registry after a restart.
     *
     * <p>The directory name cannot be split back into user and branch: both may
     * contain '-', and '/' in a branch is flattened to '_' on the way in, so
     * "alisher-feature_TASK-123-cleanup" has no unambiguous split point. The
     * branch is therefore read from the repository itself and the user from a
     * metadata file kept inside .git (never part of the working tree, so it can
     * never be committed).
     */
    private void restoreWorkspace(Path workspacePath) {
        Git git = null;
        try {
            git = Git.open(workspacePath.toFile());
            // On a detached HEAD getBranch() answers with a commit id, which would
            // register the workspace under an id nobody can ask for again
            String fullBranch = git.getRepository().getFullBranch();
            String branchName = fullBranch != null && fullBranch.startsWith(Constants.R_HEADS)
                    ? fullBranch.substring(Constants.R_HEADS.length())
                    : readWorkspaceBranch(workspacePath).orElse(null);
            if (branchName == null || branchName.isBlank()) {
                log.warn("Cannot determine branch for workspace at {}, skipping", workspacePath);
                git.close();
                return;
            }

            String userId = readWorkspaceUserId(workspacePath)
                    .orElseGet(() -> deriveUserIdFromDirName(workspacePath, branchName));
            if (userId == null) {
                log.warn("Cannot determine owner for workspace at {}, skipping", workspacePath);
                git.close();
                return;
            }

            String workspaceId = WorkspaceInfo.createId(userId, branchName);
            Instant now = Instant.now();
            WorkspaceInfo workspace = new WorkspaceInfo(
                    workspaceId,
                    userId,
                    branchName,
                    workspacePath,
                    now,
                    now
            );

            gitInstances.put(workspaceId, git);
            workspaces.put(workspaceId, workspace);
            excludeTempFilesFromGit(workspacePath);
            // Recovered through the directory-name fallback? Write the metadata so
            // the next restart does not have to guess again
            writeWorkspaceMetadata(workspacePath, userId, branchName);
            log.info("Restored workspace: {}", workspaceId);
        } catch (IOException e) {
            if (git != null) {
                git.close();
            }
            log.warn("Failed to restore workspace at {}", workspacePath, e);
        }
    }

    /**
     * Records who owns a workspace, inside .git so the file is invisible to
     * {@code git status} and can never end up in a commit.
     */
    private void writeWorkspaceMetadata(Path workspacePath, String userId, String branchName) {
        Properties props = new Properties();
        props.setProperty("userId", userId);
        props.setProperty("branchName", branchName);
        Path metadataFile = workspacePath.resolve(GIT_DIR).resolve(WORKSPACE_METADATA_FILE);
        try (var out = Files.newOutputStream(metadataFile)) {
            props.store(out, "Flow Designer workspace metadata");
        } catch (IOException e) {
            // The workspace still works; only restore-after-restart degrades
            log.warn("Failed to write workspace metadata at {}", metadataFile, e);
        }
    }

    /**
     * Keeps the atomic-write temp files out of git via .git/info/exclude — local
     * to this clone, so nothing is added to the repository's own .gitignore. A
     * temp file orphaned by a crash then cannot be staged by "git add THUB/" or
     * make the workspace look permanently dirty.
     */
    private void excludeTempFilesFromGit(Path workspacePath) {
        Path excludeFile = workspacePath.resolve(GIT_DIR).resolve("info").resolve("exclude");
        String rule = THUB_DIR + "/.*-data.json.tmp" + System.lineSeparator()
                + LAYOUT_DIR + "/**/.*.json.tmp";
        try {
            Files.createDirectories(excludeFile.getParent());
            if (Files.exists(excludeFile) && Files.readString(excludeFile).contains(rule)) {
                return;
            }
            Files.writeString(excludeFile, System.lineSeparator() + "# Flow Designer atomic write temp files"
                            + System.lineSeparator() + rule + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write git exclude rules for {}", workspacePath, e);
        }
    }

    private Optional<String> readWorkspaceUserId(Path workspacePath) {
        return readWorkspaceMetadata(workspacePath, "userId");
    }

    private Optional<String> readWorkspaceBranch(Path workspacePath) {
        return readWorkspaceMetadata(workspacePath, "branchName");
    }

    private Optional<String> readWorkspaceMetadata(Path workspacePath, String key) {
        Path metadataFile = workspacePath.resolve(GIT_DIR).resolve(WORKSPACE_METADATA_FILE);
        if (!Files.exists(metadataFile)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(metadataFile)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Failed to read workspace metadata at {}", metadataFile, e);
            return Optional.empty();
        }
        String value = props.getProperty(key);
        return value != null && !value.isBlank() ? Optional.of(value) : Optional.empty();
    }

    /**
     * Fallback for workspaces created before the metadata file existed: the
     * directory is "{userId}-{sanitized branch}", and the branch is now known,
     * so the remainder is the user.
     */
    private String deriveUserIdFromDirName(Path workspacePath, String branchName) {
        String dirName = workspacePath.getFileName().toString();
        String suffix = "-" + branchName.replace("/", "_");
        if (dirName.length() > suffix.length() && dirName.endsWith(suffix)) {
            return dirName.substring(0, dirName.length() - suffix.length());
        }
        return null;
    }

    @Override
    public WorkspaceInfo getOrCreateWorkspace(String userId, String branchName) {
        String workspaceId = WorkspaceInfo.createId(userId, branchName);
        ReentrantLock lock = workspaceLocks.computeIfAbsent(workspaceId, k -> new ReentrantLock());

        lock.lock();
        try {
            WorkspaceInfo existing = workspaces.get(workspaceId);
            if (existing != null) {
                WorkspaceInfo updated = existing.withLastAccessedAt(Instant.now());
                workspaces.put(workspaceId, updated);
                return updated;
            }

            return createWorkspace(userId, branchName, workspaceId);
        } finally {
            lock.unlock();
        }
    }

    private WorkspaceInfo createWorkspace(String userId, String branchName, String workspaceId) {
        Path workspacePath = Path.of(properties.workspacesPath(), workspaceId);

        try {
            Files.createDirectories(workspacePath.getParent());

            log.info("Creating workspace {} at {}", workspaceId, workspacePath);

            // Always clone from default branch first
            var cloneCommand = Git.cloneRepository()
                    .setURI(properties.remoteUrl())
                    .setDirectory(workspacePath.toFile())
                    .setBranch(properties.defaultBranch());

            CredentialsProvider credentials = userOrServiceCredentials();
            if (credentials != null) {
                cloneCommand.setCredentialsProvider(credentials);
            }

            Git git = cloneCommand.call();
            gitInstances.put(workspaceId, git);

            // Handle empty repo (no commits) - create initial commit
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) {
                log.info("Empty repository detected for workspace {}, creating initial commit", workspaceId);
                Path thubDir = workspacePath.resolve(THUB_DIR);
                Files.createDirectories(thubDir);
                Files.writeString(thubDir.resolve(".gitkeep"), "");
                git.add().addFilepattern(THUB_DIR + "/.gitkeep").call();
                git.commit()
                        .setSign(false)
                        .setMessage("Initial commit - create THUB directory")
                        .call();
            }

            // If target branch is different from default, try to checkout or create it
            if (!branchName.equals(properties.defaultBranch())) {
                checkoutOrCreateBranch(git, branchName);
            }

            writeWorkspaceMetadata(workspacePath, userId, branchName);
            excludeTempFilesFromGit(workspacePath);

            Instant now = Instant.now();
            WorkspaceInfo workspace = new WorkspaceInfo(
                    workspaceId,
                    userId,
                    branchName,
                    workspacePath,
                    now,
                    now
            );
            workspaces.put(workspaceId, workspace);

            log.info("Workspace {} created successfully", workspaceId);
            return workspace;
        } catch (TransportException e) {
            throw new GitAuthenticationException("Failed to clone repository for workspace", e);
        } catch (GitAPIException | IOException e) {
            throw new GitOperationException("Failed to create workspace: " + workspaceId, e);
        }
    }

    /**
     * Checkout an existing remote branch or create a new local branch.
     * If the branch exists on remote, it will be checked out.
     * If not, a new branch will be created from the current HEAD.
     */
    private void checkoutOrCreateBranch(Git git, String branchName) throws GitAPIException {
        // Check if branch exists on remote
        String remoteBranch = "origin/" + branchName;
        boolean remoteBranchExists = git.branchList()
                .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE)
                .call()
                .stream()
                .anyMatch(ref -> ref.getName().equals("refs/remotes/" + remoteBranch));

        if (remoteBranchExists) {
            // Checkout existing remote branch
            log.info("Checking out existing remote branch: {}", branchName);
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branchName)
                    .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint(remoteBranch)
                    .call();
        } else {
            // Create new local branch from current HEAD
            log.info("Creating new local branch: {}", branchName);
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branchName)
                    .call();
        }
    }

    @Override
    public Optional<WorkspaceInfo> getWorkspace(String userId, String branchName) {
        String workspaceId = WorkspaceInfo.createId(userId, branchName);
        return Optional.ofNullable(workspaces.get(workspaceId));
    }

    @Override
    public void pull(WorkspaceInfo workspace) {
        withLock(workspace, () -> {
            Git git = getGitInstance(workspace);
            try {
                // Merging into a dirty tree is what makes a pull dangerous: git
                // refuses it, and recovery from a half-done merge is a repository-wide
                // reset, so the whole tree — not just THUB/ — has to be clean first.
                requireCleanTreeForPull(git);

                ObjectId headBeforePull = git.getRepository().resolve(Constants.HEAD);
                var pullCommand = git.pull()
                        // Never inherit pull.rebase from the server user's global git
                        // config: a conflicting rebase leaves a detached HEAD and a
                        // rebase in progress, which the merge recovery below cannot undo
                        .setRebase(false);
                CredentialsProvider credentials = userOrServiceCredentials();
                if (credentials != null) {
                    pullCommand.setCredentialsProvider(credentials);
                }
                // JGit reports a failed merge in the result, it does not throw
                PullResult result = pullCommand.call();
                if (!result.isSuccessful()) {
                    // Leave no half-merged tree behind: conflict markers and
                    // MERGE_HEAD would be swept into the next commit and pushed.
                    // Safe here because the tree was clean when the merge started.
                    abortMergeIfStarted(git, headBeforePull, workspace);
                }
                verifyPullResult(result, workspace);
                updateLastAccessed(workspace);
            } catch (TransportException e) {
                throw new GitAuthenticationException("Failed to pull to workspace", e);
            } catch (GitAPIException | IOException e) {
                throw new GitOperationException("Failed to pull to workspace: " + workspace.id(), e);
            }
        });
    }

    @Override
    public void createBranch(WorkspaceInfo workspace, String branchName) {
        withLock(workspace, () -> {
            Git git = getGitInstance(workspace);
            try {
                git.branchCreate()
                        .setName(branchName)
                        .call();
                git.checkout()
                        .setName(branchName)
                        .call();
                updateLastAccessed(workspace);
            } catch (GitAPIException e) {
                throw new GitOperationException("Failed to create branch: " + branchName, e);
            }
        });
    }

    @Override
    public void addManagedFiles(WorkspaceInfo workspace) {
        add(workspace, MANAGED_DIRS.stream().map(dir -> dir + "/").toArray(String[]::new));
    }

    @Override
    public void createAndPushBranch(WorkspaceInfo workspace, String branchName) {
        withLock(workspace, () -> {
            Git git = getGitInstance(workspace);
            try {
                // Create the ref without checking out: this workspace stays on its
                // own branch, and pushing makes the new branch real so a workspace
                // for it clones the current branch's work rather than the default
                // branch's
                git.branchCreate().setName(branchName).call();

                try {
                    var pushCommand = git.push()
                            .setRefSpecs(new RefSpec(Constants.R_HEADS + branchName
                                    + ":" + Constants.R_HEADS + branchName));
                    CredentialsProvider credentials = userOrServiceCredentials();
                    if (credentials != null) {
                        pushCommand.setCredentialsProvider(credentials);
                    }
                    verifyPushResults(pushCommand.call(), workspace);
                } catch (RuntimeException | GitAPIException e) {
                    // Leave no half-created branch behind, or retrying the same
                    // name fails on the local ref rather than the real problem
                    deleteLocalBranch(git, branchName);
                    throw e;
                }
                updateLastAccessed(workspace);
            } catch (TransportException e) {
                throw new GitAuthenticationException("Failed to publish branch: " + branchName, e);
            } catch (GitAPIException e) {
                throw new GitOperationException("Failed to create branch: " + branchName, e);
            }
        });
    }

    private void deleteLocalBranch(Git git, String branchName) {
        try {
            git.branchDelete().setBranchNames(branchName).setForce(true).call();
        } catch (GitAPIException e) {
            log.warn("Failed to remove local branch {} after a failed publish", branchName, e);
        }
    }

    @Override
    public void checkout(WorkspaceInfo workspace, String branchName) {
        withLock(workspace, () -> {
            Git git = getGitInstance(workspace);
            try {
                git.checkout()
                        .setName(branchName)
                        .call();
                updateLastAccessed(workspace);
            } catch (GitAPIException e) {
                throw new GitOperationException("Failed to checkout branch: " + branchName, e);
            }
        });
    }

    @Override
    public void add(WorkspaceInfo workspace, String... filePatterns) {
        withLock(workspace, () -> {
            Git git = getGitInstance(workspace);
            try {
                var addCommand = git.add();
                for (String pattern : filePatterns) {
                    addCommand.addFilepattern(pattern);
                }
                addCommand.call();
                updateLastAccessed(workspace);
            } catch (GitAPIException e) {
                throw new GitOperationException("Failed to stage files", e);
            }
        });
    }

    @Override
    public String commit(WorkspaceInfo workspace, String message, AuditInfo auditInfo, String expectedVersion) {
        return withLockReturn(workspace, () -> {
            Git git = getGitInstance(workspace);

            // Optimistic locking check
            if (expectedVersion != null) {
                String currentHead = getHeadCommitInternal(git);
                if (!expectedVersion.equals(currentHead)) {
                    throw new GitVersionConflictException(expectedVersion, currentHead, workspace.id());
                }
            }

            try {
                // Create PersonIdent for both author and committer (user who made the change)
                PersonIdent userIdent = new PersonIdent(auditInfo.userName(), auditInfo.userEmail());

                // Append audit trailers to commit message
                String fullMessage = message + auditInfo.toTrailers();

                RevCommit commit = git.commit()
                        // Never inherit commit.gpgsign from the server user's global
                        // git config: signing would fail for every user of the app
                        .setSign(false)
                        .setMessage(fullMessage)
                        .setAuthor(userIdent)
                        .setCommitter(userIdent)  // Set committer to user for full audit trail
                        .call();

                updateLastAccessed(workspace);
                log.info("Commit created by user '{}' ({}): {}",
                        auditInfo.userId(), auditInfo.userEmail(), commit.getId().getName());

                return commit.getId().getName();
            } catch (GitAPIException e) {
                throw new GitOperationException("Failed to commit changes", e);
            }
        });
    }

    @Override
    public void push(WorkspaceInfo workspace) {
        withLock(workspace, () -> {
            Git git = getGitInstance(workspace);
            try {
                var pushCommand = git.push();
                CredentialsProvider credentials = userOrServiceCredentials();
                if (credentials != null) {
                    pushCommand.setCredentialsProvider(credentials);
                }
                // A rejected push comes back as a status on the ref update, not as an exception
                verifyPushResults(pushCommand.call(), workspace);
                configureUpstreamIfMissing(git);
                updateLastAccessed(workspace);
            } catch (TransportException e) {
                throw new GitAuthenticationException("Failed to push to remote", e);
            } catch (GitAPIException e) {
                throw new GitOperationException("Failed to push to remote", e);
            }
        });
    }

    @Override
    public String getHeadCommit(WorkspaceInfo workspace) {
        return withLockReturn(workspace, () -> {
            Git git = getGitInstance(workspace);
            updateLastAccessed(workspace);
            return getHeadCommitInternal(git);
        });
    }

    /**
     * Fails the push when the remote refused any ref update. JGit reports a
     * rejection (non-fast-forward, hook, missing permission) as a status on the
     * ref update and returns normally, so an unchecked push always looks like a
     * success to the caller.
     */
    private void verifyPushResults(Iterable<PushResult> results, WorkspaceInfo workspace) {
        List<String> rejections = new ArrayList<>();
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                if (status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE) {
                    continue;
                }
                String reason = update.getMessage() != null && !update.getMessage().isBlank()
                        ? status + " (" + update.getMessage() + ")"
                        : status.toString();
                rejections.add(update.getRemoteName() + ": " + reason);
            }
        }

        if (!rejections.isEmpty()) {
            String detail = String.join("; ", rejections);
            log.warn("Push rejected for workspace {}: {}", workspace.id(), detail);
            throw new GitSyncConflictException("push",
                    "Remote rejected the push — pull the latest changes and try again. " + detail);
        }
    }

    /**
     * Refuses a pull that could cost the user work. Names the files, and
     * distinguishes the app's own data from anything else in the clone, because
     * the two need different answers: commit the flows, remove the strays.
     */
    private void requireCleanTreeForPull(Git git) throws GitAPIException {
        Status status = git.status().call();
        if (status.isClean()) {
            return;
        }

        Set<String> changed = changedPaths(status);

        List<String> flowChanges = changed.stream().filter(GitServiceImpl::isManaged).toList();
        List<String> otherChanges = changed.stream().filter(path -> !isManaged(path)).toList();

        StringBuilder detail = new StringBuilder();
        if (!flowChanges.isEmpty()) {
            detail.append("commit your flow changes first (").append(String.join(", ", flowChanges)).append(")");
        }
        if (!otherChanges.isEmpty()) {
            if (!detail.isEmpty()) {
                detail.append("; ");
            }
            detail.append("and remove these files, which Flow Designer does not manage (")
                    .append(String.join(", ", otherChanges)).append(")");
        }

        throw new GitSyncConflictException("pull",
                "The workspace has uncommitted changes — " + detail + ".");
    }

    /**
     * Puts the working tree back where it was before a failed pull. Without this
     * the workspace keeps the conflicted files and MERGE_HEAD, and the user's next
     * commit records the conflict markers as a merge commit.
     *
     * <p>Only runs when a merge actually started. When the merge never began —
     * JGit reports CHECKOUT_CONFLICT and leaves the tree untouched — a reset here
     * would destroy the very work it is meant to protect.
     */
    private void abortMergeIfStarted(Git git, ObjectId headBeforePull, WorkspaceInfo workspace) {
        if (headBeforePull == null) {
            return;
        }
        try {
            if (git.getRepository().readMergeHeads() == null) {
                log.info("Pull for workspace {} left the working tree untouched, nothing to revert",
                        workspace.id());
                return;
            }
        } catch (IOException e) {
            log.warn("Cannot tell whether workspace {} is mid-merge, leaving it alone", workspace.id(), e);
            return;
        }
        try {
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef(headBeforePull.getName())
                    .call();
            log.info("Reverted workspace {} to {} after a failed pull",
                    workspace.id(), headBeforePull.getName());
        } catch (GitAPIException e) {
            log.error("Failed to revert workspace {} after a failed pull — it may hold conflict markers",
                    workspace.id(), e);
        }
    }

    /**
     * Fails the pull when the merge did not complete. As with push, JGit reports
     * a conflicting merge in the result instead of throwing, which would leave
     * the workspace holding conflict markers while the caller reports success.
     */
    private void verifyPullResult(PullResult result, WorkspaceInfo workspace) {
        if (result.isSuccessful()) {
            return;
        }

        StringBuilder detail = new StringBuilder();
        MergeResult mergeResult = result.getMergeResult();
        if (mergeResult != null) {
            detail.append("merge status: ").append(mergeResult.getMergeStatus());
            if (mergeResult.getConflicts() != null && !mergeResult.getConflicts().isEmpty()) {
                detail.append(", conflicting files: ")
                        .append(String.join(", ", mergeResult.getConflicts().keySet()));
            }
        } else if (result.getRebaseResult() != null) {
            detail.append("rebase status: ").append(result.getRebaseResult().getStatus());
        } else {
            detail.append("fetch result: ").append(result.getFetchResult());
        }

        log.warn("Pull failed for workspace {}: {}", workspace.id(), detail);
        throw new GitSyncConflictException("pull",
                "The remote has changes that conflict with this branch, and they cannot be merged "
                        + "automatically. The workspace was left as it was. " + detail);
    }

    /**
     * Points a locally created branch at its remote counterpart after the first
     * push. JGit's push does not write branch.&lt;name&gt;.remote/merge, and without
     * them {@link BranchTrackingStatus} reports nothing at all — the workspace
     * would keep claiming the branch was never pushed, and idle cleanup would
     * keep treating every commit on it as unpushed work.
     */
    private void configureUpstreamIfMissing(Git git) {
        try {
            Repository repo = git.getRepository();
            String branch = repo.getBranch();
            if (branch == null || branch.isBlank()) {
                return;
            }
            StoredConfig config = repo.getConfig();
            String remote = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch,
                    ConfigConstants.CONFIG_KEY_REMOTE);
            String merge = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch,
                    ConfigConstants.CONFIG_KEY_MERGE);
            if (remote != null && merge != null) {
                return;
            }
            config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch,
                    ConfigConstants.CONFIG_KEY_REMOTE, Constants.DEFAULT_REMOTE_NAME);
            config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch,
                    ConfigConstants.CONFIG_KEY_MERGE, Constants.R_HEADS + branch);
            config.save();
            log.debug("Recorded upstream origin/{} for branch {}", branch, branch);
        } catch (IOException e) {
            // Only the ahead/behind reporting degrades; the push itself succeeded
            log.warn("Failed to record upstream branch configuration", e);
        }
    }

    /**
     * Refreshes the remote-tracking refs at most once per
     * {@link #FETCH_INTERVAL}, so a status call right after a save does not wait
     * on the network.
     */
    private void fetchIfStale(Git git, WorkspaceInfo workspace) {
        Instant last = lastFetchAt.get(workspace.id());
        if (last != null && last.isAfter(Instant.now().minus(FETCH_INTERVAL))) {
            return;
        }
        // Stamped only on success: a failed fetch that consumed the window would
        // leave the behind count reading "in sync" until it expired
        if (fetchQuietly(git, workspace)) {
            lastFetchAt.put(workspace.id(), Instant.now());
        }
    }

    /**
     * Updates the remote-tracking refs, tolerating an unreachable remote: the
     * status is still useful offline, only the behind count goes stale.
     */
    private boolean fetchQuietly(Git git, WorkspaceInfo workspace) {
        try {
            var fetchCommand = git.fetch()
                    // Status runs under the workspace lock, so an unresponsive
                    // remote must not be able to block saves indefinitely
                    .setTimeout(FETCH_TIMEOUT_SECONDS);
            CredentialsProvider credentials = userOrServiceCredentials();
            if (credentials != null) {
                fetchCommand.setCredentialsProvider(credentials);
            }
            fetchCommand.call();
            return true;
        } catch (GitAPIException | RuntimeException e) {
            // JGit wraps transport and IO failures in unchecked JGitInternalException;
            // an unreachable remote must leave the status readable, only staler
            log.debug("Could not refresh remote refs for workspace {}: {}", workspace.id(), e.getMessage());
            return false;
        }
    }

    /** Whether a path belongs to a directory this application writes. */
    static boolean isManaged(String path) {
        return MANAGED_DIRS.stream().anyMatch(dir -> path.startsWith(dir + "/"));
    }

    /** Every path git would report as changed, in one sorted set. */
    private static Set<String> changedPaths(Status status) {
        Set<String> changed = new TreeSet<>();
        changed.addAll(status.getConflicting());
        changed.addAll(status.getAdded());
        changed.addAll(status.getChanged());
        changed.addAll(status.getRemoved());
        changed.addAll(status.getModified());
        changed.addAll(status.getMissing());
        changed.addAll(status.getUntracked());
        return changed;
    }

    /**
     * Whether the remote already carries this branch.
     */
    private boolean hasRemoteBranch(Repository repo, String branch) throws IOException {
        return BranchTrackingStatus.of(repo, branch) != null
                || repo.resolve(remoteRef(branch)) != null;
    }

    /**
     * Commits that exist only in this workspace.
     *
     * <p>Prefers {@link BranchTrackingStatus}. A branch created locally has no
     * upstream configured until its first push, so the count then falls back to
     * the remote branch itself and, failing that, to the remote default branch —
     * on a branch the remote has never seen, everything committed since the
     * default branch is work nobody else has. Returns -1 when the remote is
     * unknown entirely, which callers treat as "assume there is work".
     */
    private int countUnpushedCommits(Repository repo, String branch) throws IOException {
        BranchTrackingStatus tracking = BranchTrackingStatus.of(repo, branch);
        if (tracking != null) {
            return tracking.getAheadCount();
        }

        ObjectId head = repo.resolve(Constants.HEAD);
        if (head == null) {
            return 0;
        }
        ObjectId base = repo.resolve(remoteRef(branch));
        if (base == null) {
            base = repo.resolve(remoteRef(properties.defaultBranch()));
        }
        if (base == null) {
            return -1;
        }

        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(head));
            walk.markUninteresting(walk.parseCommit(base));
            int ahead = 0;
            while (walk.next() != null) {
                ahead++;
            }
            return ahead;
        }
    }

    /**
     * Commits the remote has that this workspace does not. Mirrors
     * {@link #countUnpushedCommits}: without the fallback, a branch with no
     * upstream config reports "in sync" while the remote is ahead, and the
     * user only finds out when their push is rejected.
     */
    private int countCommitsBehind(Repository repo, String branch) throws IOException {
        BranchTrackingStatus tracking = BranchTrackingStatus.of(repo, branch);
        if (tracking != null) {
            return tracking.getBehindCount();
        }

        ObjectId head = repo.resolve(Constants.HEAD);
        ObjectId remote = repo.resolve(remoteRef(branch));
        if (head == null || remote == null) {
            return 0;
        }

        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(remote));
            walk.markUninteresting(walk.parseCommit(head));
            int behind = 0;
            while (walk.next() != null) {
                behind++;
            }
            return behind;
        }
    }

    private static String remoteRef(String branch) {
        return Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + branch;
    }

    private String getHeadCommitInternal(Git git) {
        try {
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) {
                return "empty";
            }
            return head.getName();
        } catch (IOException e) {
            throw new GitOperationException("Failed to get HEAD commit", e);
        }
    }

    @Override
    public WorkspaceStatus getStatus(WorkspaceInfo workspace) {
        return withLockReturn(workspace, () -> {
            Git git = getGitInstance(workspace);
            updateLastAccessed(workspace);
            try {
                // Without a fetch the remote-tracking refs never move, so the behind
                // count would always read zero and a user would only learn of a
                // teammate's push when their own is rejected. It runs inside the
                // lock because it rewrites refs a concurrent pull or push uses, and
                // is throttled so the status call after every save does not pay for
                // a round-trip to the remote.
                fetchIfStale(git, workspace);

                // Only THUB/ is ever staged, so only THUB/ decides whether the
                // workspace is clean — otherwise an unrelated file would leave the
                // panel dirty forever and invite a chain of empty commits. Those
                // files still block a pull, so they are reported separately rather
                // than left invisible.
                var statusCommand = git.status();
                MANAGED_DIRS.forEach(statusCommand::addPath);
                Status status = statusCommand.call();

                Set<String> unmanaged = changedPaths(git.status().call()).stream()
                        .filter(path -> !isManaged(path))
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                // Everything the user could still lose, in one sorted list
                // changedPaths includes getConflicting(): without it a workspace
                // stuck mid-merge reports itself clean, hiding the files and
                // disabling the very buttons needed to get out of it
                Set<String> changed = changedPaths(status);

                Repository repo = git.getRepository();
                String branch = repo.getBranch();

                return new WorkspaceStatus(
                        getHeadCommitInternal(git),
                        branch,
                        List.copyOf(changed),
                        List.copyOf(unmanaged),
                        Math.max(countUnpushedCommits(repo, branch), 0),
                        countCommitsBehind(repo, branch),
                        hasRemoteBranch(repo, branch)
                );
            } catch (GitAPIException | IOException e) {
                throw new GitOperationException("Failed to read workspace status", e);
            }
        });
    }

    @Override
    public void cleanupWorkspace(String userId, String branchName) {
        String workspaceId = WorkspaceInfo.createId(userId, branchName);
        // computeIfAbsent, not get: a workspace restored after a restart has no
        // lock entry yet, and deleting it unlocked races with a save in flight
        ReentrantLock lock = workspaceLocks.computeIfAbsent(workspaceId, k -> new ReentrantLock());

        lock.lock();
        try {
            WorkspaceInfo workspace = workspaces.remove(workspaceId);
            if (workspace == null) {
                throw new WorkspaceNotFoundException(workspaceId);
            }

            lastFetchAt.remove(workspaceId);
            Git git = gitInstances.remove(workspaceId);
            if (git != null) {
                git.close();
            }

            deleteDirectory(workspace.path());
            // The lock stays in the map on purpose: removing it while holding it
            // lets a concurrent caller create a fresh lock and run alongside the
            // deletion — including a concurrent create, which would then clone
            // twice into the same directory. The cost is one idle lock per
            // user/branch pair seen since startup.
            log.info("Cleaned up workspace: {}", workspaceId);
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedRateString = "${app.git.main-repo-refresh-interval:PT5M}")
    public void refreshMainRepo() {
        try {
            pullMainRepo();
        } catch (Exception e) {
            log.warn("Scheduled main repo refresh failed", e);
        }
    }

    @Override
    @Scheduled(fixedRateString = "${app.git.cleanup.check-interval:PT30M}")
    public void cleanupIdleWorkspaces() {
        if (!properties.cleanup().enabled()) {
            return;
        }

        cleanupIdleWorkspacesOlderThan(Instant.now().minus(properties.cleanup().maxIdleTime()));
    }

    /**
     * Removes workspaces untouched since the cutoff, keeping any that still hold
     * work only the server has. Package-visible so the idle behaviour can be
     * tested without waiting out the configured idle time.
     */
    void cleanupIdleWorkspacesOlderThan(Instant cutoff) {
        log.debug("Checking for idle workspaces (cutoff: {})", cutoff);

        List.copyOf(workspaces.values()).stream()
                .filter(w -> w.lastAccessedAt().isBefore(cutoff))
                .forEach(workspace -> {
                    try {
                        // Check and delete under one lock hold: a save landing between
                        // the two would otherwise be deleted along with the workspace
                        withLock(workspace, () -> {
                            // The filter above read a snapshot; the user may have
                            // come back while this loop waited for the lock
                            WorkspaceInfo current = workspaces.get(workspace.id());
                            if (current == null || !current.lastAccessedAt().isBefore(cutoff)) {
                                return;
                            }
                            if (hasUnsavedWork(workspace)) {
                                log.info("Keeping idle workspace {} — it has uncommitted or unpushed work",
                                        workspace.id());
                                return;
                            }
                            cleanupWorkspace(workspace.userId(), workspace.branchName());
                        });
                    } catch (Exception e) {
                        log.warn("Failed to cleanup idle workspace: {}", workspace.id(), e);
                    }
                });
    }

    /**
     * Whether the workspace holds work that only exists on the server: files the
     * user saved but never committed, or commits never pushed to the remote.
     * Deleting such a workspace would destroy that work, so idle cleanup skips it.
     *
     * <p>Errs on the side of keeping the workspace: anything that cannot be
     * determined counts as unsaved work.
     */
    private boolean hasUnsavedWork(WorkspaceInfo workspace) {
        Git git = gitInstances.get(workspace.id());
        if (git == null) {
            return true;
        }
        try {
            if (!git.status().call().isClean()) {
                return true;
            }
            // Anything not on the remote would be destroyed with the workspace;
            // an unknown remote (-1) counts as work, so the clone is kept
            Repository repo = git.getRepository();
            return countUnpushedCommits(repo, repo.getBranch()) != 0;
        } catch (GitAPIException | IOException e) {
            log.warn("Cannot determine workspace state for {}, keeping it", workspace.id(), e);
            return true;
        }
    }

    @Override
    public Collection<WorkspaceInfo> getAllWorkspaces() {
        return workspaces.values();
    }

    @Override
    public List<String> listBranches() {
        if (mainRepo == null) {
            return List.of();
        }
        try {
            String prefix = "refs/remotes/origin/";
            return mainRepo.branchList()
                    .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE)
                    .call()
                    .stream()
                    .map(ref -> ref.getName())
                    .filter(name -> name.startsWith(prefix) && !name.endsWith("/HEAD"))
                    .map(name -> name.substring(prefix.length()))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list branches: {}", e.getMessage());
            return List.of();
        }
    }

    private Git getGitInstance(WorkspaceInfo workspace) {
        Git git = gitInstances.get(workspace.id());
        if (git == null) {
            throw new WorkspaceNotFoundException(workspace.id());
        }
        return git;
    }

    private void updateLastAccessed(WorkspaceInfo workspace) {
        workspaces.computeIfPresent(workspace.id(), (k, v) -> v.withLastAccessedAt(Instant.now()));
    }

    @Override
    public void withWorkspaceLock(WorkspaceInfo workspace, Runnable action) {
        withLock(workspace, action);
    }

    @Override
    public <T> T withWorkspaceLock(WorkspaceInfo workspace, java.util.function.Supplier<T> action) {
        return withLockReturn(workspace, action);
    }

    private void withLock(WorkspaceInfo workspace, Runnable action) {
        ReentrantLock lock = workspaceLocks.computeIfAbsent(workspace.id(), k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    private <T> T withLockReturn(WorkspaceInfo workspace, java.util.function.Supplier<T> action) {
        ReentrantLock lock = workspaceLocks.computeIfAbsent(workspace.id(), k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void deleteDirectory(Path path) {
        try {
            FileUtils.delete(path.toFile(),
                    FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        } catch (IOException e) {
            log.warn("Failed to delete directory: {}", path, e);
        }
    }
}
