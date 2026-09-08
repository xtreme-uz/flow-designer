package uz.xtreme.flowdesigner.service.git;

import uz.xtreme.flowdesigner.config.GitProperties;
import uz.xtreme.flowdesigner.exception.GitSyncConflictException;
import uz.xtreme.flowdesigner.exception.GitVersionConflictException;
import uz.xtreme.flowdesigner.exception.WorkspaceNotFoundException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GitServiceImplTest {

    @TempDir
    Path tempDir;

    private Path remoteRepoPath;
    private Path mainRepoPath;
    private Path workspacesPath;
    private GitServiceImpl gitService;

    @BeforeEach
    void setUp() throws GitAPIException, IOException, URISyntaxException {
        remoteRepoPath = tempDir.resolve("remote.git");
        mainRepoPath = tempDir.resolve("main");
        workspacesPath = tempDir.resolve("workspaces");

        // Create a bare repository to act as remote
        Git.init()
                .setDirectory(remoteRepoPath.toFile())
                .setBare(true)
                .call()
                .close();

        // Create initial commit in a temp repo and push to remote
        Path initRepoPath = tempDir.resolve("init");
        try (Git initGit = Git.init().setDirectory(initRepoPath.toFile()).call()) {
            // Create initial file
            Path flowsDir = initRepoPath.resolve("flows");
            Files.createDirectories(flowsDir);
            Files.writeString(flowsDir.resolve("sample.json"), "{\"name\": \"sample\"}");

            initGit.add().addFilepattern(".").call();
            initGit.commit()
                    // The service disables signing for the same reason: a global
                    // commit.gpgsign on the machine must not break the fixture
                    .setSign(false)
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .call();

            // Add remote and push
            initGit.remoteAdd()
                    .setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish(remoteRepoPath.toUri().toString()))
                    .call();
            initGit.push().setRemote("origin").call();
        }

        // Create GitProperties
        GitProperties properties = new GitProperties(
                remoteRepoPath.toUri().toString(),
                mainRepoPath.toString(),
                workspacesPath.toString(),
                "master",
                false,
                new GitProperties.Credentials(null, null, null),
                new GitProperties.Cleanup(Duration.ofHours(1), Duration.ofMinutes(30), true)
        );

        gitService = new GitServiceImpl(properties);
        gitService.init();
    }

    @AfterEach
    void tearDown() {
        if (gitService != null) {
            gitService.destroy();
        }
    }

    @Nested
    @DisplayName("Workspace Status Tests")
    class WorkspaceStatusTests {

        private WorkspaceInfo workspace;

        @BeforeEach
        void createWorkspace() {
            workspace = gitService.getOrCreateWorkspace("statususer", "master");
        }

        @Test
        @DisplayName("Reports a freshly cloned workspace as clean and in sync")
        void cleanWorkspace() {
            WorkspaceStatus status = gitService.getStatus(workspace);

            assertTrue(status.clean());
            assertTrue(status.changedFiles().isEmpty());
            assertEquals(0, status.aheadCount());
            assertEquals("master", status.branchName());
        }

        @Test
        @DisplayName("Lists files the user saved but has not committed")
        void reportsUncommittedFiles() throws IOException {
            Files.createDirectories(workspace.path().resolve("THUB"));
            Files.writeString(workspace.path().resolve("THUB/FlowType-data.json"), "{}");

            WorkspaceStatus status = gitService.getStatus(workspace);

            assertFalse(status.clean());
            assertTrue(status.changedFiles().contains("THUB/FlowType-data.json"));
        }

        @Test
        @DisplayName("Reports the branch as pushed once a locally created branch has been pushed")
        void reportsUpstreamAfterFirstPush() throws IOException {
            // A branch created locally has no upstream config; only the first push
            // establishes it, and without that the status is stuck on "not pushed"
            WorkspaceInfo feature = gitService.getOrCreateWorkspace("statususer", "feature/TASK-7-status");
            Files.writeString(feature.path().resolve("feature.json"), "{}");
            gitService.add(feature, ".");
            gitService.commit(feature, "Feature work",
                    AuditInfo.of("statususer", "Status User", "status@example.com"), null);

            assertEquals(1, gitService.getStatus(feature).aheadCount(),
                    "the commit is unpushed before the push");

            gitService.push(feature);
            WorkspaceStatus status = gitService.getStatus(feature);

            assertTrue(status.hasUpstream(), "the branch exists on the remote after the push");
            assertEquals(0, status.aheadCount());
        }

        @Test
        @DisplayName("Counts commits the remote has and this workspace does not")
        void reportsBehindCountWithoutUpstreamConfig() throws IOException {
            String branch = "feature/TASK-13-behind";
            WorkspaceInfo first = gitService.getOrCreateWorkspace("behind-one", branch);
            Files.writeString(first.path().resolve("first.json"), "{}");
            gitService.add(first, ".");
            gitService.commit(first, "First",
                    AuditInfo.of("one", "One", "one@example.com"), null);
            gitService.push(first);

            // A second workspace on the same branch, then the first one moves ahead
            WorkspaceInfo second = gitService.getOrCreateWorkspace("behind-two", branch);
            Files.writeString(first.path().resolve("second.json"), "{}");
            gitService.add(first, ".");
            gitService.commit(first, "Second",
                    AuditInfo.of("one", "One", "one@example.com"), null);
            gitService.push(first);

            WorkspaceStatus status = gitService.getStatus(second);

            assertEquals(1, status.behindCount(), "the remote is one commit ahead");
            assertEquals(0, status.aheadCount());
        }

        @Test
        @DisplayName("Counts commits that have not been pushed")
        void reportsUnpushedCommits() throws IOException {
            Files.writeString(workspace.path().resolve("unpushed.json"), "{}");
            gitService.add(workspace, ".");
            gitService.commit(workspace, "Local only",
                    AuditInfo.of("statususer", "Status User", "status@example.com"), null);

            WorkspaceStatus status = gitService.getStatus(workspace);

            assertTrue(status.clean());
            assertEquals(1, status.aheadCount());
            assertTrue(status.hasUpstream());
        }
    }

    @Nested
    @DisplayName("Push Rejection Tests")
    class PushRejectionTests {

        @Test
        @DisplayName("Rejected push raises a conflict instead of reporting success")
        void rejectedPushRaisesConflict() throws IOException {
            // Two workspaces on the same branch: the second one's push cannot
            // fast-forward once the first has pushed
            WorkspaceInfo first = gitService.getOrCreateWorkspace("pusher-one", "master");
            WorkspaceInfo second = gitService.getOrCreateWorkspace("pusher-two", "master");

            Files.writeString(first.path().resolve("first.json"), "{}");
            gitService.add(first, ".");
            gitService.commit(first, "First", AuditInfo.of("one", "One", "one@example.com"), null);
            gitService.push(first);

            Files.writeString(second.path().resolve("second.json"), "{}");
            gitService.add(second, ".");
            gitService.commit(second, "Second", AuditInfo.of("two", "Two", "two@example.com"), null);

            GitSyncConflictException ex = assertThrows(GitSyncConflictException.class,
                    () -> gitService.push(second));
            assertEquals("push", ex.getOperation());
        }
    }

    @Nested
    @DisplayName("Pull Conflict Tests")
    class PullConflictTests {

        @Test
        @DisplayName("A conflicting pull leaves no half-merged tree behind")
        void conflictingPullIsAborted() throws IOException, GitAPIException {
            WorkspaceInfo first = gitService.getOrCreateWorkspace("puller-one", "master");
            WorkspaceInfo second = gitService.getOrCreateWorkspace("puller-two", "master");

            // Both workspaces change the same file, and the first one gets there first
            Files.writeString(first.path().resolve("shared.json"), "{\"owner\": \"one\"}");
            gitService.add(first, ".");
            gitService.commit(first, "One", AuditInfo.of("one", "One", "one@example.com"), null);
            gitService.push(first);

            Files.writeString(second.path().resolve("shared.json"), "{\"owner\": \"two\"}");
            gitService.add(second, ".");
            gitService.commit(second, "Two", AuditInfo.of("two", "Two", "two@example.com"), null);
            String headBefore = gitService.getHeadCommit(second);

            assertThrows(GitSyncConflictException.class, () -> gitService.pull(second));

            assertEquals(headBefore, gitService.getHeadCommit(second),
                    "the workspace is back where it started");
            assertTrue(gitService.getStatus(second).clean(),
                    "no conflict markers are left staged or in the working tree");
            try (Git git = Git.open(second.path().toFile())) {
                assertNull(git.getRepository().readMergeHeads(), "the merge is not still in progress");
            }
        }
    }

    @Nested
    @DisplayName("Branch Publication Tests")
    class BranchPublicationTests {

        @Test
        @DisplayName("Publishing the same branch twice does not trip over a leftover local ref")
        void publishingLeavesNoLocalRefBehind() {
            WorkspaceInfo source = gitService.getOrCreateWorkspace("republisher", "master");
            gitService.createAndPushBranch(source, "feature/TASK-14-first");

            // The remote already has it at this commit, so the second call is a
            // no-op — it must not fail on a branch ref left behind by the first
            assertDoesNotThrow(() -> gitService.createAndPushBranch(source, "feature/TASK-14-first"));
        }

        @Test
        @DisplayName("A new branch starts from the current branch's work, not the default branch")
        void newBranchCarriesCurrentWork() throws IOException {
            WorkspaceInfo source = gitService.getOrCreateWorkspace("brancher", "feature/TASK-11-source");
            Files.writeString(source.path().resolve("source-work.json"), "{}");
            gitService.add(source, ".");
            gitService.commit(source, "Source work",
                    AuditInfo.of("brancher", "Brancher", "brancher@example.com"), null);
            gitService.push(source);

            gitService.createAndPushBranch(source, "feature/TASK-12-derived");

            assertEquals("feature/TASK-11-source", gitService.getStatus(source).branchName(),
                    "the source workspace stays on its own branch");

            WorkspaceInfo derived = gitService.getOrCreateWorkspace("brancher", "feature/TASK-12-derived");
            assertTrue(Files.exists(derived.path().resolve("source-work.json")),
                    "the new branch carries the work it was branched from");
        }
    }

    @Nested
    @DisplayName("Pull Safety Tests")
    class PullSafetyTests {

        @Test
        @DisplayName("An untracked file it does not manage does not block a pull")
        void strayUntrackedFileDoesNotBlockPull() throws IOException {
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("stray-puller", "master");
            // Nothing in the app can remove this, and reset --hard would not touch it
            Files.writeString(workspace.path().resolve("notes.txt"), "scratch");

            assertDoesNotThrow(() -> gitService.pull(workspace));
            assertTrue(Files.exists(workspace.path().resolve("notes.txt")));
        }

        @Test
        @DisplayName("Refuses to pull over uncommitted work instead of risking it")
        void refusesPullWithUncommittedChanges() throws IOException {
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("dirty-puller", "master");
            Files.createDirectories(workspace.path().resolve("THUB"));
            Files.writeString(workspace.path().resolve("THUB/FlowType-data.json"), "{\"unsaved\": true}");

            assertThrows(GitSyncConflictException.class, () -> gitService.pull(workspace));

            assertEquals("{\"unsaved\": true}",
                    Files.readString(workspace.path().resolve("THUB/FlowType-data.json")),
                    "the unsaved file is still there");
        }
    }

    @Nested
    @DisplayName("Workspace Restore Tests")
    class WorkspaceRestoreTests {

        @Test
        @DisplayName("Restores a workspace whose branch name contains dashes")
        void restoresBranchWithDashes() {
            String branch = "feature/TASK-123-cleanup";
            WorkspaceInfo created = gitService.getOrCreateWorkspace("alisher-k", branch);
            gitService.destroy();

            // A new instance over the same directory stands in for a restart
            GitServiceImpl restarted = new GitServiceImpl(new GitProperties(
                    remoteRepoPath.toUri().toString(),
                    mainRepoPath.toString(),
                    workspacesPath.toString(),
                    "master",
                    false,
                    new GitProperties.Credentials(null, null, null),
                    new GitProperties.Cleanup(Duration.ofHours(1), Duration.ofMinutes(30), true)
            ));
            restarted.init();
            try {
                Optional<WorkspaceInfo> restored = restarted.getWorkspace("alisher-k", branch);

                assertTrue(restored.isPresent(), "workspace should be found under its original user and branch");
                assertEquals(created.id(), restored.get().id());
                assertEquals(branch, restored.get().branchName());
                assertEquals("alisher-k", restored.get().userId());
            } finally {
                restarted.destroy();
                gitService = null;
            }
        }
    }

    @Nested
    @DisplayName("Idle Cleanup Tests")
    class IdleCleanupTests {

        @Test
        @DisplayName("Keeps an idle workspace that holds uncommitted work")
        void keepsWorkspaceWithUncommittedWork() throws IOException {
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("idleuser", "master");
            Files.writeString(workspace.path().resolve("unsaved.json"), "{}");

            gitService.cleanupIdleWorkspacesOlderThan(Instant.now().plusSeconds(60));

            assertTrue(gitService.getWorkspace("idleuser", "master").isPresent());
            assertTrue(Files.exists(workspace.path()));
        }

        @Test
        @DisplayName("Keeps an idle workspace that holds unpushed commits")
        void keepsWorkspaceWithUnpushedCommits() throws IOException {
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("idleuser2", "master");
            Files.writeString(workspace.path().resolve("committed.json"), "{}");
            gitService.add(workspace, ".");
            gitService.commit(workspace, "Not pushed",
                    AuditInfo.of("idleuser2", "Idle", "idle@example.com"), null);

            gitService.cleanupIdleWorkspacesOlderThan(Instant.now().plusSeconds(60));

            assertTrue(gitService.getWorkspace("idleuser2", "master").isPresent());
        }

        @Test
        @DisplayName("Removes an idle feature-branch workspace once its work is pushed")
        void removesPushedFeatureBranchWorkspace() throws IOException {
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("idleuser4", "feature/TASK-9-done");
            Files.writeString(workspace.path().resolve("done.json"), "{}");
            gitService.add(workspace, ".");
            gitService.commit(workspace, "Done",
                    AuditInfo.of("idleuser4", "Idle", "idle@example.com"), null);
            gitService.push(workspace);

            gitService.cleanupIdleWorkspacesOlderThan(Instant.now().plusSeconds(60));

            assertTrue(gitService.getWorkspace("idleuser4", "feature/TASK-9-done").isEmpty(),
                    "nothing is left to lose once the branch is pushed");
        }

        @Test
        @DisplayName("Removes an idle workspace with nothing left to lose")
        void removesCleanWorkspace() {
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("idleuser3", "master");

            gitService.cleanupIdleWorkspacesOlderThan(Instant.now().plusSeconds(60));

            assertTrue(gitService.getWorkspace("idleuser3", "master").isEmpty());
            assertFalse(Files.exists(workspace.path()));
        }
    }

    @Nested
    @DisplayName("Main Repository Tests")
    class MainRepoTests {

        @Test
        @DisplayName("Should clone main repository on init")
        void shouldCloneMainRepoOnInit() {
            assertTrue(Files.exists(mainRepoPath.resolve(".git")));
            assertTrue(Files.exists(mainRepoPath.resolve("flows/sample.json")));
        }

        @Test
        @DisplayName("Should pull latest changes to main repo")
        void shouldPullMainRepo() {
            assertDoesNotThrow(() -> gitService.pullMainRepo());
        }
    }

    @Nested
    @DisplayName("Workspace Management Tests")
    class WorkspaceTests {

        @Test
        @DisplayName("Should create new workspace for user")
        void shouldCreateWorkspace() {
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("user1", "master");

            assertNotNull(workspace);
            assertEquals("user1-master", workspace.id());
            assertEquals("user1", workspace.userId());
            assertEquals("master", workspace.branchName());
            assertTrue(Files.exists(workspace.path().resolve(".git")));
            assertTrue(Files.exists(workspace.path().resolve("flows/sample.json")));
        }

        @Test
        @DisplayName("Should return existing workspace on second call")
        void shouldReturnExistingWorkspace() {
            WorkspaceInfo first = gitService.getOrCreateWorkspace("user1", "master");
            WorkspaceInfo second = gitService.getOrCreateWorkspace("user1", "master");

            assertEquals(first.id(), second.id());
            assertEquals(first.path(), second.path());
            // Second call should update lastAccessedAt
            assertTrue(second.lastAccessedAt().compareTo(first.lastAccessedAt()) >= 0);
        }

        @Test
        @DisplayName("Should create separate workspaces for different users")
        void shouldCreateSeparateWorkspacesForDifferentUsers() {
            WorkspaceInfo workspace1 = gitService.getOrCreateWorkspace("user1", "master");
            WorkspaceInfo workspace2 = gitService.getOrCreateWorkspace("user2", "master");

            assertNotEquals(workspace1.id(), workspace2.id());
            assertNotEquals(workspace1.path(), workspace2.path());
        }

        @Test
        @DisplayName("Should create separate workspaces for different branches")
        void shouldCreateSeparateWorkspacesForDifferentBranches() throws IOException {
            // Create first workspace on master
            WorkspaceInfo workspace1 = gitService.getOrCreateWorkspace("user1", "master");

            // Create and push a new branch from workspace1
            gitService.createBranch(workspace1, "feature/test");
            Path newFile = workspace1.path().resolve("flows/feature.json");
            Files.writeString(newFile, "{\"name\": \"feature\"}");
            gitService.add(workspace1, ".");
            AuditInfo auditInfo = AuditInfo.of("user1", "User One", "user1@example.com");
            gitService.commit(workspace1, "Feature commit", auditInfo, null);
            gitService.push(workspace1);

            // Switch back to master for workspace1
            gitService.checkout(workspace1, "master");

            // Now create second workspace on the feature branch
            WorkspaceInfo workspace2 = gitService.getOrCreateWorkspace("user1", "feature/test");

            assertNotEquals(workspace1.id(), workspace2.id());
            assertEquals("user1-feature_test", workspace2.id()); // slash replaced with underscore
        }

        @Test
        @DisplayName("Should get existing workspace")
        void shouldGetExistingWorkspace() {
            gitService.getOrCreateWorkspace("user1", "master");

            Optional<WorkspaceInfo> found = gitService.getWorkspace("user1", "master");

            assertTrue(found.isPresent());
            assertEquals("user1-master", found.get().id());
        }

        @Test
        @DisplayName("Should return empty for non-existent workspace")
        void shouldReturnEmptyForNonExistentWorkspace() {
            Optional<WorkspaceInfo> found = gitService.getWorkspace("nonexistent", "master");

            assertTrue(found.isEmpty());
        }

        @Test
        @DisplayName("Should list all workspaces")
        void shouldListAllWorkspaces() {
            // Create workspaces for different users on the same branch
            gitService.getOrCreateWorkspace("user1", "master");
            gitService.getOrCreateWorkspace("user2", "master");
            gitService.getOrCreateWorkspace("user3", "master");

            Collection<WorkspaceInfo> all = gitService.getAllWorkspaces();

            assertEquals(3, all.size());
        }

        @Test
        @DisplayName("Should cleanup specific workspace")
        void shouldCleanupWorkspace() {
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("user1", "master");
            Path workspacePath = workspace.path();

            gitService.cleanupWorkspace("user1", "master");

            assertFalse(Files.exists(workspacePath));
            assertTrue(gitService.getWorkspace("user1", "master").isEmpty());
        }

        @Test
        @DisplayName("Should throw exception when cleaning up non-existent workspace")
        void shouldThrowOnCleanupNonExistent() {
            assertThrows(WorkspaceNotFoundException.class,
                    () -> gitService.cleanupWorkspace("nonexistent", "master"));
        }

        @Test
        @DisplayName("Should create workspace for non-existent branch by creating new local branch")
        void shouldCreateWorkspaceForNonExistentBranch() {
            // Create workspace for a branch that doesn't exist on remote
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("user1", "feature/new-branch");

            assertNotNull(workspace);
            assertEquals("user1", workspace.userId());
            assertEquals("feature/new-branch", workspace.branchName());
            assertTrue(Files.exists(workspace.path()));
        }
    }

    @Nested
    @DisplayName("Git Operations Tests")
    class GitOperationsTests {

        private WorkspaceInfo workspace;

        @BeforeEach
        void setUpWorkspace() {
            workspace = gitService.getOrCreateWorkspace("testuser", "master");
        }

        @Test
        @DisplayName("Should get HEAD commit")
        void shouldGetHeadCommit() {
            String head = gitService.getHeadCommit(workspace);

            assertNotNull(head);
            assertEquals(40, head.length()); // SHA-1 hash length
        }

        @Test
        @DisplayName("Should add and commit files")
        void shouldAddAndCommit() throws IOException {
            // Create a new file
            Path newFile = workspace.path().resolve("flows/new-flow.json");
            Files.writeString(newFile, "{\"name\": \"new-flow\"}");

            String beforeCommit = gitService.getHeadCommit(workspace);

            // Add and commit
            gitService.add(workspace, "flows/new-flow.json");
            AuditInfo auditInfo = AuditInfo.of("testuser", "Test User", "test@example.com", "127.0.0.1");
            String commitHash = gitService.commit(workspace, "Add new flow", auditInfo, null);

            assertNotNull(commitHash);
            assertNotEquals(beforeCommit, commitHash);
            assertEquals(commitHash, gitService.getHeadCommit(workspace));
        }

        @Test
        @DisplayName("Should add all files with dot pattern")
        void shouldAddAllFiles() throws IOException {
            Path file1 = workspace.path().resolve("flows/flow1.json");
            Path file2 = workspace.path().resolve("flows/flow2.json");
            Files.writeString(file1, "{\"name\": \"flow1\"}");
            Files.writeString(file2, "{\"name\": \"flow2\"}");

            gitService.add(workspace, ".");
            AuditInfo auditInfo = AuditInfo.of("testuser", "Test User", "test@example.com");
            String commitHash = gitService.commit(workspace, "Add multiple flows", auditInfo, null);

            assertNotNull(commitHash);
        }

        @Test
        @DisplayName("Should push commits to remote")
        void shouldPushToRemote() throws IOException {
            Path newFile = workspace.path().resolve("flows/pushed.json");
            Files.writeString(newFile, "{\"name\": \"pushed\"}");

            gitService.add(workspace, ".");
            AuditInfo auditInfo = AuditInfo.of("testuser", "Test User", "test@example.com");
            gitService.commit(workspace, "Add pushed flow", auditInfo, null);

            assertDoesNotThrow(() -> gitService.push(workspace));
        }

        @Test
        @DisplayName("Should pull changes from remote")
        void shouldPullFromRemote() {
            assertDoesNotThrow(() -> gitService.pull(workspace));
        }

        @Test
        @DisplayName("Should create and checkout new branch")
        void shouldCreateBranch() {
            gitService.createBranch(workspace, "feature/new-feature");

            // Verify we're on the new branch by making a commit
            assertDoesNotThrow(() -> {
                try {
                    Path newFile = workspace.path().resolve("flows/feature.json");
                    Files.writeString(newFile, "{\"name\": \"feature\"}");
                    gitService.add(workspace, ".");
                    AuditInfo auditInfo = AuditInfo.of("testuser", "Test User", "test@example.com");
                    gitService.commit(workspace, "Feature commit", auditInfo, null);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Test
        @DisplayName("Should checkout existing branch")
        void shouldCheckoutBranch() {
            gitService.createBranch(workspace, "feature/checkout-test");
            gitService.checkout(workspace, "master");

            // Should be able to switch back
            assertDoesNotThrow(() -> gitService.checkout(workspace, "feature/checkout-test"));
        }
    }

    @Nested
    @DisplayName("Optimistic Locking Tests")
    class OptimisticLockingTests {

        private WorkspaceInfo workspace;

        @BeforeEach
        void setUpWorkspace() {
            workspace = gitService.getOrCreateWorkspace("lockuser", "master");
        }

        @Test
        @DisplayName("Should commit when expected version matches")
        void shouldCommitWhenVersionMatches() throws IOException {
            String currentHead = gitService.getHeadCommit(workspace);

            Path newFile = workspace.path().resolve("flows/versioned.json");
            Files.writeString(newFile, "{\"name\": \"versioned\"}");

            gitService.add(workspace, ".");
            AuditInfo auditInfo = AuditInfo.of("lockuser", "Lock User", "lock@example.com");
            String newCommit = gitService.commit(workspace, "Versioned commit", auditInfo, currentHead);

            assertNotNull(newCommit);
            assertNotEquals(currentHead, newCommit);
        }

        @Test
        @DisplayName("Should throw GitVersionConflictException when version mismatches")
        void shouldThrowOnVersionMismatch() throws IOException {
            Path newFile = workspace.path().resolve("flows/conflict.json");
            Files.writeString(newFile, "{\"name\": \"conflict\"}");

            gitService.add(workspace, ".");
            AuditInfo auditInfo = AuditInfo.of("lockuser", "Lock User", "lock@example.com");

            String wrongVersion = "0000000000000000000000000000000000000000";

            GitVersionConflictException exception = assertThrows(
                    GitVersionConflictException.class,
                    () -> gitService.commit(workspace, "Conflict commit", auditInfo, wrongVersion)
            );

            assertEquals(wrongVersion, exception.getExpectedVersion());
            assertNotNull(exception.getActualVersion());
            assertEquals("lockuser-master", exception.getWorkspaceId());
        }

        @Test
        @DisplayName("Should allow commit without version check when expectedVersion is null")
        void shouldAllowCommitWithoutVersionCheck() throws IOException {
            Path newFile = workspace.path().resolve("flows/nocheck.json");
            Files.writeString(newFile, "{\"name\": \"nocheck\"}");

            gitService.add(workspace, ".");
            AuditInfo auditInfo = AuditInfo.of("lockuser", "Lock User", "lock@example.com");

            String commitHash = gitService.commit(workspace, "No version check", auditInfo, null);

            assertNotNull(commitHash);
        }

        @Test
        @DisplayName("Should detect concurrent modification")
        void shouldDetectConcurrentModification() throws IOException {
            // Simulate scenario where user fetches version, then another commit happens
            String originalHead = gitService.getHeadCommit(workspace);

            // First commit (simulates another user's commit)
            Path file1 = workspace.path().resolve("flows/first.json");
            Files.writeString(file1, "{\"name\": \"first\"}");
            gitService.add(workspace, ".");
            AuditInfo audit1 = AuditInfo.of("user1", "User One", "user1@example.com");
            gitService.commit(workspace, "First commit", audit1, null);

            // Second commit with stale version should fail
            Path file2 = workspace.path().resolve("flows/second.json");
            Files.writeString(file2, "{\"name\": \"second\"}");
            gitService.add(workspace, ".");
            AuditInfo audit2 = AuditInfo.of("user2", "User Two", "user2@example.com");

            assertThrows(GitVersionConflictException.class,
                    () -> gitService.commit(workspace, "Second commit", audit2, originalHead));
        }
    }

    @Nested
    @DisplayName("Concurrency Tests")
    class ConcurrencyTests {

        @Test
        @DisplayName("Should handle concurrent workspace access safely")
        void shouldHandleConcurrentAccess() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        // All threads try to get/create the same workspace
                        WorkspaceInfo workspace = gitService.getOrCreateWorkspace("concurrent-user", "master");
                        assertNotNull(workspace);

                        // Each thread creates a unique file
                        Path file = workspace.path().resolve("flows/concurrent-" + index + ".json");
                        Files.writeString(file, "{\"index\": " + index + "}");

                        gitService.add(workspace, "flows/concurrent-" + index + ".json");

                        // Try to commit - some may fail due to version conflicts, which is expected
                        try {
                            String head = gitService.getHeadCommit(workspace);
                            AuditInfo auditInfo = AuditInfo.of("user" + index, "User " + index, "user" + index + "@example.com");
                            gitService.commit(workspace, "Concurrent commit " + index, auditInfo, head);
                            successCount.incrementAndGet();
                        } catch (GitVersionConflictException e) {
                            // Expected in concurrent scenario
                            successCount.incrementAndGet(); // Still counts as successful handling
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            // All operations should complete without unexpected errors
            assertEquals(0, errorCount.get());
            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("Should handle concurrent workspace creation for different users")
        void shouldHandleConcurrentWorkspaceCreation() throws InterruptedException {
            int userCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(userCount);
            CountDownLatch latch = new CountDownLatch(userCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < userCount; i++) {
                final String userId = "user" + i;
                executor.submit(() -> {
                    try {
                        WorkspaceInfo workspace = gitService.getOrCreateWorkspace(userId, "master");
                        assertNotNull(workspace);
                        assertEquals(userId + "-master", workspace.id());
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(0, errorCount.get());
            assertEquals(userCount, gitService.getAllWorkspaces().size());
        }
    }

    @Nested
    @DisplayName("Audit Trail Tests")
    class AuditTrailTests {

        private WorkspaceInfo workspace;

        @BeforeEach
        void setUpWorkspace() {
            workspace = gitService.getOrCreateWorkspace("audituser", "master");
        }

        @Test
        @DisplayName("Should include audit info in commit")
        void shouldIncludeAuditInfoInCommit() throws IOException, GitAPIException {
            Path newFile = workspace.path().resolve("flows/audited.json");
            Files.writeString(newFile, "{\"name\": \"audited\"}");

            gitService.add(workspace, ".");
            AuditInfo auditInfo = AuditInfo.of("audit123", "Audit User", "audit@example.com", "192.168.1.100");
            String commitHash = gitService.commit(workspace, "Audited commit", auditInfo, null);

            // Read the commit to verify audit info
            try (Git git = Git.open(workspace.path().toFile())) {
                var log = git.log().setMaxCount(1).call();
                var commit = log.iterator().next();

                // Verify author and committer are set to the user
                assertEquals("Audit User", commit.getAuthorIdent().getName());
                assertEquals("audit@example.com", commit.getAuthorIdent().getEmailAddress());
                assertEquals("Audit User", commit.getCommitterIdent().getName());
                assertEquals("audit@example.com", commit.getCommitterIdent().getEmailAddress());

                // Verify audit trailers in message
                String message = commit.getFullMessage();
                assertTrue(message.contains("User-Id: audit123"));
                assertTrue(message.contains("Client-IP: 192.168.1.100"));
                assertTrue(message.contains("Timestamp:"));
            }
        }

        @Test
        @DisplayName("Should work without IP address")
        void shouldWorkWithoutIpAddress() throws IOException, GitAPIException {
            Path newFile = workspace.path().resolve("flows/no-ip.json");
            Files.writeString(newFile, "{\"name\": \"no-ip\"}");

            gitService.add(workspace, ".");
            AuditInfo auditInfo = AuditInfo.of("user456", "No IP User", "noip@example.com");
            gitService.commit(workspace, "No IP commit", auditInfo, null);

            try (Git git = Git.open(workspace.path().toFile())) {
                var log = git.log().setMaxCount(1).call();
                var commit = log.iterator().next();
                String message = commit.getFullMessage();

                assertTrue(message.contains("User-Id: user456"));
                assertFalse(message.contains("Client-IP:"));
            }
        }

    }

    @Nested
    @DisplayName("WorkspaceInfo Tests")
    class WorkspaceInfoTests {

        @Test
        @DisplayName("Should create correct workspace ID")
        void shouldCreateCorrectId() {
            assertEquals("user1-master", WorkspaceInfo.createId("user1", "master"));
            assertEquals("user1-feature_branch", WorkspaceInfo.createId("user1", "feature/branch"));
            assertEquals("user1-feature_nested_branch", WorkspaceInfo.createId("user1", "feature/nested/branch"));
        }

        @Test
        @DisplayName("Should update lastAccessedAt")
        void shouldUpdateLastAccessedAt() throws InterruptedException {
            WorkspaceInfo workspace = gitService.getOrCreateWorkspace("timeuser", "master");
            var firstAccess = workspace.lastAccessedAt();

            Thread.sleep(10); // Small delay to ensure different timestamp

            gitService.getHeadCommit(workspace);
            WorkspaceInfo updated = gitService.getWorkspace("timeuser", "master").orElseThrow();

            assertTrue(updated.lastAccessedAt().isAfter(firstAccess) ||
                    updated.lastAccessedAt().equals(firstAccess));
        }
    }
}
