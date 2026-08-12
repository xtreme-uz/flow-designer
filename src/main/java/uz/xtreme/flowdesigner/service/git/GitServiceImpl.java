package uz.xtreme.flowdesigner.service.git;

import uz.xtreme.flowdesigner.config.GitProperties;
import uz.xtreme.flowdesigner.exception.GitAuthenticationException;
import uz.xtreme.flowdesigner.exception.GitOperationException;
import uz.xtreme.flowdesigner.exception.GitVersionConflictException;
import uz.xtreme.flowdesigner.exception.WorkspaceNotFoundException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Implementation of GitService with per-user workspaces and optimistic locking.
 */
@Service
public class GitServiceImpl implements GitService {

    private static final Logger log = LoggerFactory.getLogger(GitServiceImpl.class);

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

            if (Files.exists(mainPath.resolve(".git"))) {
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
            pullCommand.call();
            log.debug("Main repository updated successfully");
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
                    .filter(p -> Files.exists(p.resolve(".git")))
                    .forEach(this::restoreWorkspace);
        } catch (IOException e) {
            log.warn("Failed to restore workspaces", e);
        }
    }

    private void restoreWorkspace(Path workspacePath) {
        String dirName = workspacePath.getFileName().toString();
        int lastDash = dirName.lastIndexOf('-');
        if (lastDash <= 0) {
            log.warn("Cannot parse workspace directory name: {}", dirName);
            return;
        }

        String userId = dirName.substring(0, lastDash);
        String branchName = dirName.substring(lastDash + 1).replace("_", "/");

        try {
            Git git = Git.open(workspacePath.toFile());
            String workspaceId = WorkspaceInfo.createId(userId, branchName);

            WorkspaceInfo workspace = new WorkspaceInfo(
                    workspaceId,
                    userId,
                    branchName,
                    workspacePath,
                    Instant.now(),
                    Instant.now()
            );

            gitInstances.put(workspaceId, git);
            workspaces.put(workspaceId, workspace);
            log.info("Restored workspace: {}", workspaceId);
        } catch (IOException e) {
            log.warn("Failed to restore workspace at {}", workspacePath, e);
        }
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
                Path flowsDir = workspacePath.resolve("flows");
                Files.createDirectories(flowsDir);
                Files.writeString(flowsDir.resolve(".gitkeep"), "");
                git.add().addFilepattern("flows/.gitkeep").call();
                git.commit().setMessage("Initial commit - create flows directory").call();
            }

            // If target branch is different from default, try to checkout or create it
            if (!branchName.equals(properties.defaultBranch())) {
                checkoutOrCreateBranch(git, branchName);
            }

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
                pullCommand.call();
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
    @Deprecated
    public String commit(WorkspaceInfo workspace, String message, String authorName, String authorEmail, String expectedVersion) {
        // Delegate to new method with minimal audit info
        AuditInfo auditInfo = AuditInfo.of("unknown", authorName, authorEmail);
        return commit(workspace, message, auditInfo, expectedVersion);
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
                pushCommand.call();
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
            workspaceLocks.remove(workspaceId);
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

        Instant cutoff = Instant.now().minus(properties.cleanup().maxIdleTime());
        log.debug("Checking for idle workspaces (cutoff: {})", cutoff);

        workspaces.values().stream()
                .filter(w -> w.lastAccessedAt().isBefore(cutoff))
                .forEach(workspace -> {
                    try {
                        cleanupWorkspace(workspace.userId(), workspace.branchName());
                    } catch (Exception e) {
                        log.warn("Failed to cleanup idle workspace: {}", workspace.id(), e);
                    }
                });
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
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            log.warn("Failed to delete directory: {}", path, e);
        }
    }
}
