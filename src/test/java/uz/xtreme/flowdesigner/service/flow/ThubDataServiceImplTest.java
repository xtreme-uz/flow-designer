package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.service.flow.dto.thub.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThubDataServiceImplTest {

    @TempDir
    Path tempDir;

    private ThubDataServiceImpl service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ThubDataServiceImpl(objectMapper);
    }

    // ==================== Key Generation Tests ====================

    @Nested
    @DisplayName("Key Generation")
    class KeyGenerationTests {

        @Test
        @DisplayName("Should generate FlowType key")
        void flowTypeKey() {
            assertEquals("R_payment-flow", ThubDataService.flowTypeKey("payment-flow"));
        }

        @Test
        @DisplayName("Should generate FlowStatus key")
        void flowStatusKey() {
            assertEquals("R_ACCEPTED", ThubDataService.flowStatusKey("ACCEPTED"));
        }

        @Test
        @DisplayName("Should generate FlowStatusAction key")
        void flowStatusActionKey() {
            assertEquals("R_payment-flow_ACCEPTED",
                    ThubDataService.flowStatusActionKey("payment-flow", "ACCEPTED"));
        }

        @Test
        @DisplayName("Should generate FlowStatusTransition key")
        void flowStatusTransitionKey() {
            assertEquals("R_payment-flow_ACCEPTED_SRC_DEBITED",
                    ThubDataService.flowStatusTransitionKey("payment-flow", "ACCEPTED", "SRC_DEBITED"));
        }

        @Test
        @DisplayName("Should generate FlowAssignment key")
        void flowAssignmentKey() {
            assertEquals("R_assign1", ThubDataService.flowAssignmentKey("assign1"));
        }
    }

    // ==================== Read/Write FlowType Tests ====================

    @Nested
    @DisplayName("FlowType Read/Write")
    class FlowTypeTests {

        @Test
        @DisplayName("Should return empty map when no data file exists")
        void readEmpty() {
            Map<String, ThubFlowType> result = service.readFlowTypes(tempDir);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should write and read FlowType data")
        void writeAndRead() {
            ThubFlowType flowType = ThubFlowType.create("payment-flow", "Payment flow", "admin");

            Map<String, ThubFlowType> data = new LinkedHashMap<>();
            data.put("R_payment-flow", flowType);

            service.writeFlowTypes(tempDir, data);
            Map<String, ThubFlowType> result = service.readFlowTypes(tempDir);

            assertEquals(1, result.size());
            assertTrue(result.containsKey("R_payment-flow"));
            assertEquals("payment-flow", result.get("R_payment-flow").id());
            assertEquals("Payment flow", result.get("R_payment-flow").description());
            assertEquals("admin", result.get("R_payment-flow").createdBy());
        }

        @Test
        @DisplayName("Should write multiple FlowTypes")
        void writeMultiple() {
            Map<String, ThubFlowType> data = new LinkedHashMap<>();
            data.put("R_flow1", ThubFlowType.create("flow1", "Flow 1", "user1"));
            data.put("R_flow2", ThubFlowType.create("flow2", "Flow 2", "user2"));

            service.writeFlowTypes(tempDir, data);
            Map<String, ThubFlowType> result = service.readFlowTypes(tempDir);

            assertEquals(2, result.size());
        }
    }

    // ==================== Read/Write FlowStatus Tests ====================

    @Nested
    @DisplayName("FlowStatus Read/Write")
    class FlowStatusTests {

        @Test
        @DisplayName("Should write and read FlowStatus data")
        void writeAndRead() {
            Map<String, ThubFlowStatus> data = new LinkedHashMap<>();
            data.put("R_ACCEPTED", new ThubFlowStatus("ACCEPTED", "Payment accepted"));
            data.put("R_FINISHED", new ThubFlowStatus("FINISHED", "Flow completed"));

            service.writeFlowStatuses(tempDir, data);
            Map<String, ThubFlowStatus> result = service.readFlowStatuses(tempDir);

            assertEquals(2, result.size());
            assertEquals("Payment accepted", result.get("R_ACCEPTED").description());
        }
    }

    // ==================== Read/Write FlowStatusAction Tests ====================

    @Nested
    @DisplayName("FlowStatusAction Read/Write")
    class FlowStatusActionTests {

        @Test
        @DisplayName("Should write and read FlowStatusAction with flowtypeid")
        void writeAndReadWithFlowTypeId() {
            Map<String, ThubFlowStatusAction> data = new LinkedHashMap<>();
            data.put("R_payment-flow_ACCEPTED", new ThubFlowStatusAction(
                    "payment-flow", "ACCEPTED",
                    "source-payment-actor", "realize-debit",
                    null, "30m", "10m"
            ));

            service.writeFlowStatusActions(tempDir, data);
            Map<String, ThubFlowStatusAction> result = service.readFlowStatusActions(tempDir);

            assertEquals(1, result.size());
            ThubFlowStatusAction action = result.get("R_payment-flow_ACCEPTED");
            assertNotNull(action);
            assertEquals("payment-flow", action.flowTypeId());
            assertEquals("ACCEPTED", action.flowStatusId());
            assertEquals("source-payment-actor", action.actionModuleId());
            assertEquals("realize-debit", action.actionId());
        }
    }

    // ==================== Read/Write FlowStatusTransition Tests ====================

    @Nested
    @DisplayName("FlowStatusTransition Read/Write")
    class FlowStatusTransitionTests {

        @Test
        @DisplayName("Should write and read FlowStatusTransition with flowtypeid")
        void writeAndReadWithFlowTypeId() {
            Map<String, ThubFlowStatusTransition> data = new LinkedHashMap<>();
            data.put("R_payment-flow_ACCEPTED_SRC_DEBITED", new ThubFlowStatusTransition(
                    "payment-flow", "ACCEPTED", "SRC_DEBITED",
                    "success", true
            ));

            service.writeFlowStatusTransitions(tempDir, data);
            Map<String, ThubFlowStatusTransition> result = service.readFlowStatusTransitions(tempDir);

            assertEquals(1, result.size());
            ThubFlowStatusTransition transition = result.get("R_payment-flow_ACCEPTED_SRC_DEBITED");
            assertNotNull(transition);
            assertEquals("payment-flow", transition.flowTypeId());
            assertEquals("ACCEPTED", transition.flowStatusId());
            assertEquals("SRC_DEBITED", transition.nextFlowStatusId());
        }
    }

    // ==================== Data File Format Tests ====================

    @Nested
    @DisplayName("Data File Format")
    class DataFileFormatTests {

        @Test
        @DisplayName("Should write proper configurator JSON format")
        void verifyJsonFormat() throws IOException {
            Map<String, ThubFlowStatus> data = new LinkedHashMap<>();
            data.put("R_ACCEPTED", new ThubFlowStatus("ACCEPTED", "Payment accepted"));

            service.writeFlowStatuses(tempDir, data);

            String json = Files.readString(tempDir.resolve("THUB/FlowStatus-data.json"));
            assertTrue(json.contains("\"dataEntities\""));
            assertTrue(json.contains("\"R_ACCEPTED\""));
            assertTrue(json.contains("\"ACCEPTED\""));
        }

        @Test
        @DisplayName("Should handle empty data entities")
        void handleEmptyData() {
            service.writeFlowStatuses(tempDir, new LinkedHashMap<>());
            Map<String, ThubFlowStatus> result = service.readFlowStatuses(tempDir);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== FlowAssignment Tests ====================

    @Nested
    @DisplayName("FlowAssignment Read/Write")
    class FlowAssignmentTests {

        @Test
        @DisplayName("Should write and read FlowAssignment data")
        void writeAndRead() {
            Map<String, ThubFlowAssignment> data = new LinkedHashMap<>();
            data.put("R_assign1", new ThubFlowAssignment(
                    "assign1", "actor-source", "actor-target",
                    "debit-credit", "payment-flow"
            ));

            service.writeFlowAssignments(tempDir, data);
            Map<String, ThubFlowAssignment> result = service.readFlowAssignments(tempDir);

            assertEquals(1, result.size());
            ThubFlowAssignment assignment = result.get("R_assign1");
            assertEquals("payment-flow", assignment.flowTypeId());
        }
    }
}
