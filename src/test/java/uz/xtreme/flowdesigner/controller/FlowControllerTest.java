package uz.xtreme.flowdesigner.controller;

import uz.xtreme.flowdesigner.config.GitProperties;
import uz.xtreme.flowdesigner.exception.FlowNotFoundException;
import uz.xtreme.flowdesigner.exception.FlowValidationException;
import uz.xtreme.flowdesigner.exception.WorkspaceNotFoundException;
import uz.xtreme.flowdesigner.service.flow.FlowService;
import uz.xtreme.flowdesigner.service.flow.dto.FlowSummary;
import uz.xtreme.flowdesigner.service.flow.dto.thub.*;
import uz.xtreme.flowdesigner.service.git.AuditInfo;
import uz.xtreme.flowdesigner.service.git.GitService;
import uz.xtreme.flowdesigner.service.git.WorkspaceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlowControllerTest {

    @Mock
    FlowService flowService;

    @Mock
    GitService gitService;

    FlowController controller;

    static final String USER_ID = "testuser";
    static final String BRANCH = "feature/test";
    static final String FLOW_NAME = "test-flow";

    WorkspaceInfo workspace;

    @BeforeEach
    void setUp() {
        var gitProperties = new GitProperties(null, null, null, "main", null, null);
        controller = new FlowController(flowService, gitService, gitProperties);
        workspace = new WorkspaceInfo(
                "testuser-feature_test",
                USER_ID,
                BRANCH,
                Path.of("/tmp/workspaces/testuser-feature_test"),
                Instant.now(),
                Instant.now()
        );
    }

    // ==================== Test Data Helpers ====================

    private ThubDeploymentData createValidDeploymentData(String flowTypeId) {
        ThubFlowType flowType = new ThubFlowType(
                flowTypeId, "ACCEPTED", "FINISHED",
                "Test flow", "1.0", "THUB",
                USER_ID, Instant.now(), USER_ID, Instant.now(),
                Map.of()
        );
        List<ThubFlowStatus> statuses = List.of(
                new ThubFlowStatus("ACCEPTED", "Payment accepted"),
                new ThubFlowStatus("FINISHED", "Completed")
        );
        List<ThubFlowStatusAction> actions = List.of(
                new ThubFlowStatusAction(flowTypeId, "ACCEPTED", "payment", "process", 3, "PT30S", "PT15S")
        );
        List<ThubFlowStatusTransition> transitions = List.of(
                new ThubFlowStatusTransition(flowTypeId, "ACCEPTED", "FINISHED", "success", true)
        );
        return new ThubDeploymentData(flowType, statuses, actions, transitions, List.of());
    }

    private FlowSummary createFlowSummary(String name) {
        return new FlowSummary(name, name, "Test flow", "1.0", USER_ID, Instant.now());
    }

    // ==================== Main Branch Tests ====================

    @Nested
    @DisplayName("Main Branch Endpoints")
    class MainBranchTests {

        @Test
        @DisplayName("GET /api/flows - list flows from main")
        void listFlows() {
            List<FlowSummary> expected = List.of(
                    createFlowSummary("flow1"),
                    createFlowSummary("flow2")
            );
            when(flowService.listFlowsFromMain()).thenReturn(expected);

            List<FlowSummary> result = controller.listFlows();

            assertEquals(2, result.size());
            verify(flowService).listFlowsFromMain();
        }

        @Test
        @DisplayName("GET /api/flows/{name} - get flow from main returns ThubDeploymentData")
        void getFlow() {
            ThubDeploymentData deploymentData = createValidDeploymentData(FLOW_NAME);
            when(flowService.getFlowFromMain(FLOW_NAME)).thenReturn(Optional.of(deploymentData));

            ThubDeploymentData result = controller.getFlow(FLOW_NAME);

            assertNotNull(result);
            assertEquals(FLOW_NAME, result.flowType().id());
            assertEquals(2, result.flowStatuses().size());
            verify(flowService).getFlowFromMain(FLOW_NAME);
        }

        @Test
        @DisplayName("GET /api/flows/{name} - throws when not found")
        void getFlowNotFound() {
            when(flowService.getFlowFromMain(FLOW_NAME)).thenReturn(Optional.empty());

            assertThrows(FlowNotFoundException.class, () -> controller.getFlow(FLOW_NAME));
        }
    }

    // ==================== Workspace Management Tests ====================

    @Nested
    @DisplayName("Workspace Management")
    class WorkspaceManagementTests {

        @Test
        @DisplayName("POST /api/workspaces - create workspace")
        void createWorkspace() {
            when(gitService.getOrCreateWorkspace(USER_ID, BRANCH)).thenReturn(workspace);

            ResponseEntity<WorkspaceInfo> response = controller.getOrCreateWorkspace(
                    USER_ID,
                    new FlowController.CreateWorkspaceRequest(BRANCH)
            );

            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(USER_ID, response.getBody().userId());
            verify(gitService).getOrCreateWorkspace(USER_ID, BRANCH);
        }

        @Test
        @DisplayName("POST /api/workspaces - validates branch name")
        void createWorkspaceInvalidBranch() {
            assertThrows(FlowValidationException.class, () ->
                    controller.getOrCreateWorkspace(USER_ID,
                            new FlowController.CreateWorkspaceRequest("123-invalid")));
        }

        @Test
        @DisplayName("GET /api/workspaces - list user workspaces")
        void listWorkspaces() {
            WorkspaceInfo other = new WorkspaceInfo(
                    "other-main", "other", "main",
                    Path.of("/tmp/other"), Instant.now(), Instant.now()
            );
            when(gitService.getAllWorkspaces()).thenReturn(List.of(workspace, other));

            List<WorkspaceInfo> result = controller.listWorkspaces(USER_ID);

            assertEquals(1, result.size());
            assertEquals(USER_ID, result.getFirst().userId());
        }

        @Test
        @DisplayName("GET /api/workspaces/current - get current workspace")
        void getCurrentWorkspace() {
            when(gitService.getWorkspace(USER_ID, BRANCH)).thenReturn(Optional.of(workspace));

            WorkspaceInfo result = controller.getCurrentWorkspace(USER_ID, BRANCH);

            assertNotNull(result);
            assertEquals(workspace.id(), result.id());
        }

        @Test
        @DisplayName("GET /api/workspaces/current - throws when not found")
        void getCurrentWorkspaceNotFound() {
            when(gitService.getWorkspace(USER_ID, BRANCH)).thenReturn(Optional.empty());

            assertThrows(WorkspaceNotFoundException.class, () ->
                    controller.getCurrentWorkspace(USER_ID, BRANCH));
        }

        @Test
        @DisplayName("DELETE /api/workspaces - delete workspace")
        void deleteWorkspace() {
            ResponseEntity<Void> response = controller.deleteWorkspace(USER_ID, BRANCH);

            assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
            verify(gitService).cleanupWorkspace(USER_ID, BRANCH);
        }
    }

    // ==================== Workspace Flow Operations Tests ====================

    @Nested
    @DisplayName("Workspace Flow Operations")
    class WorkspaceFlowTests {

        @BeforeEach
        void setupWorkspace() {
            when(gitService.getWorkspace(USER_ID, BRANCH)).thenReturn(Optional.of(workspace));
        }

        @Test
        @DisplayName("GET /api/workspaces/flows - list flows in workspace")
        void listWorkspaceFlows() {
            List<FlowSummary> expected = List.of(createFlowSummary(FLOW_NAME));
            when(flowService.listFlows(workspace)).thenReturn(expected);

            List<FlowSummary> result = controller.listWorkspaceFlows(USER_ID, BRANCH);

            assertEquals(1, result.size());
            verify(flowService).listFlows(workspace);
        }

        @Test
        @DisplayName("GET /api/workspaces/flows/{name} - get flow returns ThubDeploymentData")
        void getWorkspaceFlow() {
            ThubDeploymentData deploymentData = createValidDeploymentData(FLOW_NAME);
            when(flowService.getFlow(workspace, FLOW_NAME)).thenReturn(Optional.of(deploymentData));

            ThubDeploymentData result = controller.getWorkspaceFlow(USER_ID, BRANCH, FLOW_NAME);

            assertNotNull(result);
            assertEquals(FLOW_NAME, result.flowType().id());
            assertEquals(2, result.flowStatuses().size());
        }

        @Test
        @DisplayName("GET /api/workspaces/flows/{name} - throws when not found")
        void getWorkspaceFlowNotFound() {
            when(flowService.getFlow(workspace, FLOW_NAME)).thenReturn(Optional.empty());

            assertThrows(FlowNotFoundException.class, () ->
                    controller.getWorkspaceFlow(USER_ID, BRANCH, FLOW_NAME));
        }

        @Test
        @DisplayName("POST /api/workspaces/flows - create flow with ThubDeploymentData")
        void createFlow() {
            ThubDeploymentData deploymentData = createValidDeploymentData(FLOW_NAME);
            when(flowService.flowExists(workspace, FLOW_NAME)).thenReturn(false);

            ResponseEntity<FlowSummary> response = controller.createFlow(
                    USER_ID, BRANCH,
                    new FlowController.CreateFlowRequest(FLOW_NAME, deploymentData)
            );

            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(FLOW_NAME, response.getBody().flowTypeId());
            verify(flowService).saveFlow(workspace, FLOW_NAME, deploymentData);
        }

        @Test
        @DisplayName("POST /api/workspaces/flows - throws when flow exists")
        void createFlowAlreadyExists() {
            when(flowService.flowExists(workspace, FLOW_NAME)).thenReturn(true);

            assertThrows(FlowValidationException.class, () ->
                    controller.createFlow(USER_ID, BRANCH,
                            new FlowController.CreateFlowRequest(FLOW_NAME, createValidDeploymentData(FLOW_NAME))));
        }

        @Test
        @DisplayName("PUT /api/workspaces/flows/{name} - update flow with ThubDeploymentData")
        void updateFlow() {
            ThubDeploymentData deploymentData = createValidDeploymentData(FLOW_NAME);
            when(flowService.flowExists(workspace, FLOW_NAME)).thenReturn(true);

            FlowSummary result = controller.updateFlow(
                    USER_ID, BRANCH, FLOW_NAME,
                    new FlowController.UpdateFlowRequest(deploymentData)
            );

            assertNotNull(result);
            assertEquals(FLOW_NAME, result.flowTypeId());
            // Verify saveFlow is called with updated lastModifiedBy
            verify(flowService).saveFlow(eq(workspace), eq(FLOW_NAME), any(ThubDeploymentData.class));
        }

        @Test
        @DisplayName("PUT /api/workspaces/flows/{name} - sets lastModifiedBy from header")
        void updateFlowSetsLastModifiedBy() {
            ThubDeploymentData deploymentData = createValidDeploymentData(FLOW_NAME);
            when(flowService.flowExists(workspace, FLOW_NAME)).thenReturn(true);

            controller.updateFlow(USER_ID, BRANCH, FLOW_NAME,
                    new FlowController.UpdateFlowRequest(deploymentData));

            verify(flowService).saveFlow(eq(workspace), eq(FLOW_NAME), argThat(data ->
                    USER_ID.equals(data.flowType().lastModifiedBy())
            ));
        }

        @Test
        @DisplayName("PUT /api/workspaces/flows/{name} - throws when not found")
        void updateFlowNotFound() {
            when(flowService.flowExists(workspace, FLOW_NAME)).thenReturn(false);

            assertThrows(FlowNotFoundException.class, () ->
                    controller.updateFlow(USER_ID, BRANCH, FLOW_NAME,
                            new FlowController.UpdateFlowRequest(createValidDeploymentData(FLOW_NAME))));
        }

        @Test
        @DisplayName("DELETE /api/workspaces/flows/{name} - delete flow")
        void deleteFlow() {
            when(flowService.deleteFlow(workspace, FLOW_NAME)).thenReturn(true);

            ResponseEntity<Void> response = controller.deleteFlow(USER_ID, BRANCH, FLOW_NAME);

            assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
            verify(flowService).deleteFlow(workspace, FLOW_NAME);
        }

        @Test
        @DisplayName("DELETE /api/workspaces/flows/{name} - throws when not found")
        void deleteFlowNotFound() {
            when(flowService.deleteFlow(workspace, FLOW_NAME)).thenReturn(false);

            assertThrows(FlowNotFoundException.class, () ->
                    controller.deleteFlow(USER_ID, BRANCH, FLOW_NAME));
        }

        @Test
        @DisplayName("POST /api/workspaces/flows/{name}/rename - rename flow")
        void renameFlow() {
            String newName = "new-flow-name";
            ThubDeploymentData renamedData = createValidDeploymentData(newName);
            when(flowService.getFlow(workspace, newName)).thenReturn(Optional.of(renamedData));

            FlowSummary result = controller.renameFlow(
                    USER_ID, BRANCH, FLOW_NAME,
                    new FlowController.RenameFlowRequest(newName)
            );

            assertNotNull(result);
            assertEquals(newName, result.name());
            verify(flowService).renameFlow(workspace, FLOW_NAME, newName);
        }

        @Test
        @DisplayName("POST /api/workspaces/flows/{name}/rename - returns fallback when flow not found after rename")
        void renameFlowFallback() {
            String newName = "new-flow-name";
            when(flowService.getFlow(workspace, newName)).thenReturn(Optional.empty());

            FlowSummary result = controller.renameFlow(
                    USER_ID, BRANCH, FLOW_NAME,
                    new FlowController.RenameFlowRequest(newName)
            );

            assertNotNull(result);
            assertEquals(newName, result.name());
            verify(flowService).renameFlow(workspace, FLOW_NAME, newName);
        }
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("POST /api/flows/validate - valid flow")
        void validateValidFlow() {
            ThubDeploymentData deploymentData = createValidDeploymentData(FLOW_NAME);
            when(flowService.validateFlow(deploymentData)).thenReturn(List.of());

            FlowController.ValidationResponse result = controller.validateFlow(deploymentData);

            assertTrue(result.valid());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("POST /api/flows/validate - invalid flow")
        void validateInvalidFlow() {
            ThubDeploymentData invalidData = new ThubDeploymentData(null, List.of(), List.of(), List.of(), List.of());
            when(flowService.validateFlow(invalidData)).thenReturn(List.of("Flow type cannot be null"));

            FlowController.ValidationResponse result = controller.validateFlow(invalidData);

            assertFalse(result.valid());
            assertFalse(result.errors().isEmpty());
        }
    }

    // ==================== Git Operations Tests ====================

    @Nested
    @DisplayName("Git Operations")
    class GitOperationsTests {

        @BeforeEach
        void setupWorkspace() {
            when(gitService.getWorkspace(USER_ID, BRANCH)).thenReturn(Optional.of(workspace));
        }

        @Test
        @DisplayName("POST /api/workspaces/commit - commit changes stages THUB/")
        void commitChanges() {
            String commitHash = "abc123";
            String message = "Test commit";
            when(gitService.commit(eq(workspace), eq(message), any(AuditInfo.class), isNull()))
                    .thenReturn(commitHash);

            FlowController.CommitResponse result = controller.commitChanges(
                    USER_ID, "Test User", "test@example.com", BRANCH,
                    new FlowController.CommitRequest(message)
            );

            assertEquals(commitHash, result.commitHash());
            assertEquals(message, result.message());
            verify(gitService).add(workspace, "THUB/");
            verify(gitService).commit(eq(workspace), eq(message), any(AuditInfo.class), isNull());
        }

        @Test
        @DisplayName("POST /api/workspaces/push - push changes")
        void pushChanges() {
            FlowController.PushResponse result = controller.pushChanges(USER_ID, BRANCH);

            assertTrue(result.success());
            verify(gitService).push(workspace);
        }

        @Test
        @DisplayName("POST /api/workspaces/pull - pull changes")
        void pullChanges() {
            FlowController.PullResponse result = controller.pullChanges(USER_ID, BRANCH);

            assertTrue(result.success());
            verify(gitService).pull(workspace);
        }

        @Test
        @DisplayName("GET /api/workspaces/status - get workspace status")
        void getWorkspaceStatus() {
            String headCommit = "abc123";
            when(gitService.getHeadCommit(workspace)).thenReturn(headCommit);

            Map<String, Object> result = controller.getWorkspaceStatus(USER_ID, BRANCH);

            assertEquals(workspace.id(), result.get("workspaceId"));
            assertEquals(workspace.branchName(), result.get("branch"));
            assertEquals(headCommit, result.get("currentVersion"));
        }

        @Test
        @DisplayName("POST /api/workspaces/branch - create new branch")
        void createBranch() {
            String newBranch = "feature/new-feature";
            WorkspaceInfo newWorkspace = new WorkspaceInfo(
                    "testuser-feature_new-feature",
                    USER_ID, newBranch,
                    Path.of("/tmp/workspaces/testuser-feature_new-feature"),
                    Instant.now(), Instant.now()
            );
            when(gitService.getOrCreateWorkspace(USER_ID, newBranch)).thenReturn(newWorkspace);

            ResponseEntity<WorkspaceInfo> response = controller.createBranch(
                    USER_ID, BRANCH,
                    new FlowController.CreateBranchRequest(newBranch)
            );

            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            assertEquals(newBranch, response.getBody().branchName());
            verify(gitService).createBranch(workspace, newBranch);
            verify(gitService).getOrCreateWorkspace(USER_ID, newBranch);
        }

    }

    // ==================== Branch Name Validation Tests ====================

    @Nested
    @DisplayName("Branch Name Validation")
    class BranchNameValidationTests {

        @Test
        @DisplayName("Accepts valid branch names")
        void validBranchNames() {
            when(gitService.getOrCreateWorkspace(anyString(), anyString())).thenReturn(workspace);

            assertDoesNotThrow(() -> controller.getOrCreateWorkspace(USER_ID,
                    new FlowController.CreateWorkspaceRequest("main")));
            assertDoesNotThrow(() -> controller.getOrCreateWorkspace(USER_ID,
                    new FlowController.CreateWorkspaceRequest("feature/test")));
            assertDoesNotThrow(() -> controller.getOrCreateWorkspace(USER_ID,
                    new FlowController.CreateWorkspaceRequest("feature/TASK-123")));
            assertDoesNotThrow(() -> controller.getOrCreateWorkspace(USER_ID,
                    new FlowController.CreateWorkspaceRequest("release-1_0")));
        }

        @Test
        @DisplayName("Rejects invalid branch names")
        void invalidBranchNames() {
            assertThrows(FlowValidationException.class, () ->
                    controller.getOrCreateWorkspace(USER_ID,
                            new FlowController.CreateWorkspaceRequest("")));
            assertThrows(FlowValidationException.class, () ->
                    controller.getOrCreateWorkspace(USER_ID,
                            new FlowController.CreateWorkspaceRequest("123-starts-with-number")));
            assertThrows(FlowValidationException.class, () ->
                    controller.getOrCreateWorkspace(USER_ID,
                            new FlowController.CreateWorkspaceRequest("has spaces")));
            assertThrows(FlowValidationException.class, () ->
                    controller.getOrCreateWorkspace(USER_ID,
                            new FlowController.CreateWorkspaceRequest("has.dots")));
        }

        @Test
        @DisplayName("Rejects invalid branch names for createBranch")
        void createBranchInvalidName() {
            assertThrows(FlowValidationException.class, () ->
                    controller.createBranch(USER_ID, BRANCH,
                            new FlowController.CreateBranchRequest("")));
        }
    }
}
