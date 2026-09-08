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
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final GitProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkspaceInfo> workspaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Git> gitInstances = new ConcurrentHashMap<>();

    private Git mainRepo;
    private CredentialsProvider credentialsProvider;

    public GitServiceImpl(GitProperties properties) {
        this.properties = properties;
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
            String branchName = git.getRepository().getBranch();
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

    private Optional<String> readWorkspaceUserId(Path workspacePath) {
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
        String userId = props.getProperty("userId");
        return userId != null && !userId.isBlank() ? Optional.of(userId) : Optional.empty();
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

            if (credentialsProvider != null) {
                cloneCommand.setCredentialsProvider(credentialsProvider);
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
                var pullCommand = git.pull();
                if (credentialsProvider != null) {
                    pullCommand.setCredentialsProvider(credentialsProvider);
                }
                // JGit reports a failed merge in the result, it does not throw
                PullResult result = pullCommand.call();
                verifyPullResult(result, workspace);
                updateLastAccessed(workspace);
            } catch (TransportException e) {
                throw new GitAuthenticationException("Failed to pull to workspace", e);
            } catch (GitAPIException e) {
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
                if (credentialsProvider != null) {
                    pushCommand.setCredentialsProvider(credentialsProvider);
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
                "Pull did not complete — resolve the conflict in the workspace and try again. " + detail);
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
                Status status = git.status().call();
                // Everything the user could still lose, in one sorted list
                Set<String> changed = new TreeSet<>();
                changed.addAll(status.getAdded());
                changed.addAll(status.getChanged());
                changed.addAll(status.getRemoved());
                changed.addAll(status.getModified());
                changed.addAll(status.getMissing());
                changed.addAll(status.getUntracked());

                Repository repo = git.getRepository();
                String branch = repo.getBranch();
                BranchTrackingStatus tracking = BranchTrackingStatus.of(repo, branch);

                return new WorkspaceStatus(
                        getHeadCommitInternal(git),
                        branch,
                        List.copyOf(changed),
                        Math.max(countUnpushedCommits(repo, branch), 0),
                        tracking != null ? tracking.getBehindCount() : 0,
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
        ReentrantLock lock = workspaceLocks.get(workspaceId);

        if (lock != null) {
            lock.lock();
        }
        try {
            WorkspaceInfo workspace = workspaces.remove(workspaceId);
            if (workspace == null) {
                throw new WorkspaceNotFoundException(workspaceId);
            }

            Git git = gitInstances.remove(workspaceId);
            if (git != null) {
                git.close();
            }

            deleteDirectory(workspace.path());
            // The lock stays in the map on purpose: removing it while holding it
            // lets a concurrent caller create a fresh lock and run alongside the
            // deletion. One idle lock per user/branch is cheap.
            log.info("Cleaned up workspace: {}", workspaceId);
        } finally {
            if (lock != null) {
                lock.unlock();
            }
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
