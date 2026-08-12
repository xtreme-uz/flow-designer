package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.config.GitProperties;
import uz.xtreme.flowdesigner.exception.FlowNotFoundException;
import uz.xtreme.flowdesigner.exception.FlowValidationException;
import uz.xtreme.flowdesigner.service.flow.dto.FlowSummary;
import uz.xtreme.flowdesigner.service.flow.dto.thub.*;
import uz.xtreme.flowdesigner.service.git.GitService;
import uz.xtreme.flowdesigner.service.git.WorkspaceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlowServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    GitService gitService;

    private Path mainRepoPath;
    private Path workspacePath;
    private GitProperties gitProperties;
    private ThubDataServiceImpl thubDataService;
    private FlowServiceImpl flowService;
    private WorkspaceInfo workspace;

    @BeforeEach
    void setUp() throws IOException {
        mainRepoPath = tempDir.resolve("main");
        workspacePath = tempDir.resolve("workspaces/user1-feature_test");
        Files.createDirectories(mainRepoPath);
        Files.createDirectories(workspacePath);

        gitProperties = new GitProperties(
                "file://" + tempDir.resolve("remote.git"),
                mainRepoPath.toString(),
                tempDir.resolve("workspaces").toString(),
                "main",
                new GitProperties.Credentials(null, null, null),
                new GitProperties.Cleanup(Duration.ofHours(1), Duration.ofMinutes(30), true)
        );

        ObjectMapper objectMapper = new ObjectMapper();
        thubDataService = new ThubDataServiceImpl(objectMapper);
        flowService = new FlowServiceImpl(gitProperties, gitService, thubDataService);

        workspace = new WorkspaceInfo(
                "user1-feature_test",
                "user1",
                "feature/test",
                workspacePath,
                Instant.now(),
                Instant.now()
        );
    }

    // ==================== Test Data Helpers ====================

    private ThubDeploymentData createValidDeploymentData(String flowTypeId) {
        ThubFlowType flowType = new ThubFlowType(
                flowTypeId, "ACCEPTED", "FINISHED", "Test flow",
                "1.0", "THUB", "testUser", Instant.now(), "testUser", Instant.now(), Map.of()
        );

        List<ThubFlowStatus> statuses = List.of(
                new ThubFlowStatus("ACCEPTED", "Payment accepted"),
                new ThubFlowStatus("SRC_DEBITED", "Source debited"),
                new ThubFlowStatus("FINISHED", "Flow completed")
        );

        List<ThubFlowStatusAction> actions = List.of(
                new ThubFlowStatusAction(flowTypeId, "ACCEPTED",
                        "source-payment-actor", "realize-debit", null, "30m", "10m"),
                new ThubFlowStatusAction(flowTypeId, "SRC_DEBITED",
                        "target-payment-actor", "realize-credit", null, null, null)
        );

        List<ThubFlowStatusTransition> transitions = List.of(
                new ThubFlowStatusTransition(flowTypeId, "ACCEPTED", "SRC_DEBITED", "success", true),
                new ThubFlowStatusTransition(flowTypeId, "SRC_DEBITED", "FINISHED", "success", true)
        );

        return new ThubDeploymentData(flowType, statuses, actions, transitions, List.of());
    }

    private void saveTestFlow(Path basePath, String flowTypeId) {
        ThubDeploymentData data = createValidDeploymentData(flowTypeId);
        Map<String, ThubFlowType> flowTypes = thubDataService.readFlowTypes(basePath);
        flowTypes.put(ThubDataService.flowTypeKey(flowTypeId), data.flowType());
        thubDataService.writeFlowTypes(basePath, flowTypes);

        Map<String, ThubFlowStatus> statuses = thubDataService.readFlowStatuses(basePath);
        for (ThubFlowStatus s : data.flowStatuses()) {
            statuses.put(ThubDataService.flowStatusKey(s.id()), s);
        }
        thubDataService.writeFlowStatuses(basePath, statuses);

        Map<String, ThubFlowStatusAction> actions = thubDataService.readFlowStatusActions(basePath);
        for (ThubFlowStatusAction a : data.flowStatusActions()) {
            actions.put(ThubDataService.flowStatusActionKey(flowTypeId, a.flowStatusId()), a);
        }
        thubDataService.writeFlowStatusActions(basePath, actions);

        Map<String, ThubFlowStatusTransition> transitions = thubDataService.readFlowStatusTransitions(basePath);
        for (ThubFlowStatusTransition t : data.flowStatusTransitions()) {
            transitions.put(ThubDataService.flowStatusTransitionKey(flowTypeId, t.flowStatusId(), t.nextFlowStatusId()), t);
        }
        thubDataService.writeFlowStatusTransitions(basePath, transitions);
    }

    // ==================== List Flows Tests ====================

    @Nested
    @DisplayName("List Flows Tests")
    class ListFlowsTests {

        @Test
        @DisplayName("Should list flows from main repository")
        void listFlowsFromMain() {
            saveTestFlow(mainRepoPath, "payment-flow");
            saveTestFlow(mainRepoPath, "transfer-flow");

            List<FlowSummary> flows = flowService.listFlowsFromMain();

            assertEquals(2, flows.size());
            assertTrue(flows.stream().anyMatch(f -> f.name().equals("payment-flow")));
            assertTrue(flows.stream().anyMatch(f -> f.name().equals("transfer-flow")));
            verify(gitService).pullMainRepo();
        }

        @Test
        @DisplayName("Should return empty list when no flows exist")
        void listFlowsFromMainEmpty() {
            List<FlowSummary> flows = flowService.listFlowsFromMain();
            assertTrue(flows.isEmpty());
            verify(gitService).pullMainRepo();
        }

        @Test
        @DisplayName("Should list flows from workspace")
        void listFlowsFromWorkspace() {
            saveTestFlow(workspacePath, "workspace-flow");

            List<FlowSummary> flows = flowService.listFlows(workspace);

            assertEquals(1, flows.size());
            assertEquals("workspace-flow", flows.getFirst().name());
        }

        @Test
        @DisplayName("Should return sorted list of flows")
        void listFlowsSorted() {
            saveTestFlow(workspacePath, "zebra-flow");
            saveTestFlow(workspacePath, "alpha-flow");
            saveTestFlow(workspacePath, "beta-flow");

            List<FlowSummary> flows = flowService.listFlows(workspace);

            assertEquals(3, flows.size());
            assertEquals("alpha-flow", flows.get(0).name());
            assertEquals("beta-flow", flows.get(1).name());
            assertEquals("zebra-flow", flows.get(2).name());
        }
    }

    // ==================== Get Flow Tests ====================

    @Nested
    @DisplayName("Get Flow Tests")
    class GetFlowTests {

        @Test
        @DisplayName("Should get flow data from main repository")
        void getFlowFromMain() {
            saveTestFlow(mainRepoPath, "test-flow");

            Optional<ThubDeploymentData> result = flowService.getFlowFromMain("test-flow");

            assertTrue(result.isPresent());
            assertEquals("test-flow", result.get().flowType().id());
            assertEquals(3, result.get().flowStatuses().size());
            assertEquals(2, result.get().flowStatusActions().size());
            assertEquals(2, result.get().flowStatusTransitions().size());
            verify(gitService).pullMainRepo();
        }

        @Test
        @DisplayName("Should return empty when flow not found in main")
        void getFlowFromMainNotFound() {
            Optional<ThubDeploymentData> result = flowService.getFlowFromMain("non-existent");
            assertTrue(result.isEmpty());
            verify(gitService).pullMainRepo();
        }

        @Test
        @DisplayName("Should get flow data from workspace")
        void getFlowFromWorkspace() {
            saveTestFlow(workspacePath, "workspace-flow");

            Optional<ThubDeploymentData> result = flowService.getFlow(workspace, "workspace-flow");

            assertTrue(result.isPresent());
            assertNotNull(result.get().flowType());
            assertNotNull(result.get().flowStatuses());
        }

        @Test
        @DisplayName("Should check flow exists")
        void flowExists() {
            saveTestFlow(workspacePath, "existing-flow");

            assertTrue(flowService.flowExists(workspace, "existing-flow"));
            assertFalse(flowService.flowExists(workspace, "non-existent"));
        }

        @Test
        @DisplayName("Should filter actions and transitions by flowTypeId")
        void filterByFlowTypeId() {
            saveTestFlow(workspacePath, "flow-a");
            saveTestFlow(workspacePath, "flow-b");

            Optional<ThubDeploymentData> flowA = flowService.getFlow(workspace, "flow-a");
            assertTrue(flowA.isPresent());

            // Should only have actions/transitions for flow-a
            flowA.get().flowStatusActions().forEach(a ->
                    assertEquals("flow-a", a.flowTypeId()));
            flowA.get().flowStatusTransitions().forEach(t ->
                    assertEquals("flow-a", t.flowTypeId()));
        }
    }

    // ==================== Save Flow Tests ====================

    @Nested
    @DisplayName("Save Flow Tests")
    class SaveFlowTests {

        @Test
        @DisplayName("Should save flow to workspace")
        void saveFlow() {
            ThubDeploymentData data = createValidDeploymentData("new-flow");

            flowService.saveFlow(workspace, "new-flow", data);

            assertTrue(flowService.flowExists(workspace, "new-flow"));
            Optional<ThubDeploymentData> saved = flowService.getFlow(workspace, "new-flow");
            assertTrue(saved.isPresent());
            assertEquals(3, saved.get().flowStatuses().size());
            assertEquals(2, saved.get().flowStatusActions().size());
        }

        @Test
        @DisplayName("Should throw validation error for invalid flow name")
        void saveFlowInvalidName() {
            ThubDeploymentData data = createValidDeploymentData("test");

            assertThrows(FlowValidationException.class, () ->
                    flowService.saveFlow(workspace, "123-invalid", data));
            assertThrows(FlowValidationException.class, () ->
                    flowService.saveFlow(workspace, "", data));
            assertThrows(FlowValidationException.class, () ->
                    flowService.saveFlow(workspace, "invalid name", data));
        }

        @Test
        @DisplayName("Should throw validation error for invalid flow data")
        void saveFlowInvalidData() {
            ThubDeploymentData emptyData = new ThubDeploymentData(
                    ThubFlowType.create("test", null, "user"),
                    List.of(), List.of(), List.of(), List.of()
            );

            assertThrows(FlowValidationException.class, () ->
                    flowService.saveFlow(workspace, "test-flow", emptyData));
        }

        @Test
        @DisplayName("Should create THUB directory if not exists")
        void saveFlowCreatesDirectory() throws IOException {
            Path newWorkspacePath = tempDir.resolve("new-workspace");
            Files.createDirectories(newWorkspacePath);
            WorkspaceInfo newWorkspace = new WorkspaceInfo(
                    "new-workspace", "user2", "feature/new",
                    newWorkspacePath, Instant.now(), Instant.now()
            );

            ThubDeploymentData data = createValidDeploymentData("new-flow");
            flowService.saveFlow(newWorkspace, "new-flow", data);

            assertTrue(Files.exists(newWorkspacePath.resolve("THUB")));
            assertTrue(flowService.flowExists(newWorkspace, "new-flow"));
        }

        @Test
        @DisplayName("Should merge shared statuses from multiple flows")
        void mergeSharedStatuses() {
            ThubDeploymentData flow1 = createValidDeploymentData("flow-one");
            flowService.saveFlow(workspace, "flow-one", flow1);

            // flow-two shares ACCEPTED and FINISHED statuses
            ThubDeploymentData flow2 = createValidDeploymentData("flow-two");
            flowService.saveFlow(workspace, "flow-two", flow2);

            // Both flows should be readable
            assertTrue(flowService.flowExists(workspace, "flow-one"));
            assertTrue(flowService.flowExists(workspace, "flow-two"));

            // Shared statuses file should have 3 unique statuses (not 6)
            Map<String, ThubFlowStatus> allStatuses = thubDataService.readFlowStatuses(workspacePath);
            assertEquals(3, allStatuses.size()); // ACCEPTED, SRC_DEBITED, FINISHED
        }
    }

    // ==================== Delete Flow Tests ====================

    @Nested
    @DisplayName("Delete Flow Tests")
    class DeleteFlowTests {

        @Test
        @DisplayName("Should delete flow from workspace")
        void deleteFlow() {
            saveTestFlow(workspacePath, "to-delete");
            assertTrue(flowService.flowExists(workspace, "to-delete"));

            boolean deleted = flowService.deleteFlow(workspace, "to-delete");

            assertTrue(deleted);
            assertFalse(flowService.flowExists(workspace, "to-delete"));
        }

        @Test
        @DisplayName("Should return false when deleting non-existent flow")
        void deleteNonExistentFlow() {
            boolean deleted = flowService.deleteFlow(workspace, "non-existent");
            assertFalse(deleted);
        }

        @Test
        @DisplayName("Should preserve shared statuses when deleting a flow")
        void deletePreservesSharedStatuses() {
            saveTestFlow(workspacePath, "flow-a");
            saveTestFlow(workspacePath, "flow-b");

            // Delete flow-a
            flowService.deleteFlow(workspace, "flow-a");

            // flow-b should still work
            assertTrue(flowService.flowExists(workspace, "flow-b"));
            Optional<ThubDeploymentData> flowB = flowService.getFlow(workspace, "flow-b");
            assertTrue(flowB.isPresent());
            assertEquals(3, flowB.get().flowStatuses().size()); // Statuses preserved

            // Statuses should still be in shared file
            Map<String, ThubFlowStatus> allStatuses = thubDataService.readFlowStatuses(workspacePath);
            assertFalse(allStatuses.isEmpty());
        }
    }

    // ==================== Rename Flow Tests ====================

    @Nested
    @DisplayName("Rename Flow Tests")
    class RenameFlowTests {

        @Test
        @DisplayName("Should rename flow in workspace")
        void renameFlow() {
            saveTestFlow(workspacePath, "old-name");

            flowService.renameFlow(workspace, "old-name", "new-name");

            assertFalse(flowService.flowExists(workspace, "old-name"));
            assertTrue(flowService.flowExists(workspace, "new-name"));

            Optional<ThubDeploymentData> renamed = flowService.getFlow(workspace, "new-name");
            assertTrue(renamed.isPresent());
            assertEquals("new-name", renamed.get().flowType().id());
        }

        @Test
        @DisplayName("Should throw when source flow not found")
        void renameNonExistentFlow() {
            assertThrows(FlowNotFoundException.class, () ->
                    flowService.renameFlow(workspace, "non-existent", "new-name"));
        }

        @Test
        @DisplayName("Should throw when target name already exists")
        void renameToExistingName() {
            saveTestFlow(workspacePath, "flow-a");
            saveTestFlow(workspacePath, "flow-b");

            assertThrows(FlowValidationException.class, () ->
                    flowService.renameFlow(workspace, "flow-a", "flow-b"));
        }

        @Test
        @DisplayName("Should throw for invalid new name")
        void renameToInvalidName() {
            saveTestFlow(workspacePath, "valid-flow");

            assertThrows(FlowValidationException.class, () ->
                    flowService.renameFlow(workspace, "valid-flow", "invalid name"));
        }
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should validate valid flow data")
        void validateValidFlow() {
            ThubDeploymentData data = createValidDeploymentData("test-flow");
            List<String> errors = flowService.validateFlow(data);
            assertTrue(errors.isEmpty());
        }

        @Test
        @DisplayName("Should detect missing initial status")
        void validateMissingInitialStatus() {
            ThubFlowType flowType = new ThubFlowType(
                    "test", null, "FINISHED", "Test",
                    "1.0", "THUB", null, null, null, null, null
            );
            ThubDeploymentData data = new ThubDeploymentData(
                    flowType,
                    List.of(new ThubFlowStatus("FINISHED", "Done")),
                    List.of(), List.of(), List.of()
            );

            List<String> errors = flowService.validateFlow(data);
            assertTrue(errors.stream().anyMatch(e -> e.contains("initial status")));
        }

        @Test
        @DisplayName("Should detect missing final status")
        void validateMissingFinalStatus() {
            ThubFlowType flowType = new ThubFlowType(
                    "test", "ACCEPTED", null, "Test",
                    "1.0", "THUB", null, null, null, null, null
            );
            ThubDeploymentData data = new ThubDeploymentData(
                    flowType,
                    List.of(new ThubFlowStatus("ACCEPTED", "Start")),
                    List.of(), List.of(), List.of()
            );

            List<String> errors = flowService.validateFlow(data);
            assertTrue(errors.stream().anyMatch(e -> e.contains("final status")));
        }

        @Test
        @DisplayName("Should detect transitions referencing non-existent statuses")
        void validateInvalidTransitionReferences() {
            ThubFlowType flowType = new ThubFlowType(
                    "test", "ACCEPTED", "FINISHED", "Test",
                    "1.0", "THUB", null, null, null, null, null
            );
            ThubDeploymentData data = new ThubDeploymentData(
                    flowType,
                    List.of(new ThubFlowStatus("ACCEPTED", "Start"), new ThubFlowStatus("FINISHED", "End")),
                    List.of(),
                    List.of(new ThubFlowStatusTransition("test", "ACCEPTED", "NON_EXISTENT", "success", true)),
                    List.of()
            );

            List<String> errors = flowService.validateFlow(data);
            assertTrue(errors.stream().anyMatch(e -> e.contains("non-existent next status")));
        }

        @Test
        @DisplayName("Should validate flow names")
        void validateFlowNames() {
            assertTrue(flowService.validateFlowName("valid-flow").isEmpty());
            assertTrue(flowService.validateFlowName("ValidFlow123").isEmpty());
            assertTrue(flowService.validateFlowName("flow_name").isEmpty());

            assertFalse(flowService.validateFlowName("").isEmpty());
            assertFalse(flowService.validateFlowName("123-starts-with-number").isEmpty());
            assertFalse(flowService.validateFlowName("has spaces").isEmpty());
            assertFalse(flowService.validateFlowName("has.dots").isEmpty());
            assertFalse(flowService.validateFlowName("a".repeat(101)).isEmpty());
        }
    }

    // ==================== Error Handling Tests ====================

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should return empty for non-existent flow")
        void handleNonExistentFlow() {
            Optional<ThubDeploymentData> result = flowService.getFlow(workspace, "non-existent");
            assertTrue(result.isEmpty());
        }
    }
}
