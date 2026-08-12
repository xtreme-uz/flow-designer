package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.service.flow.dto.thub.*;

import java.nio.file.Path;
import java.util.Map;

/**
 * Service for reading/writing shared THUB data files.
 * Each THUB table has 3 files: metadata.json, definition.json, data.json.
 * This service manages the data.json files which contain dataEntities.
 */
public interface ThubDataService {

    // ==================== Read Operations ====================

    Map<String, ThubFlowType> readFlowTypes(Path basePath);

    Map<String, ThubFlowStatus> readFlowStatuses(Path basePath);

    Map<String, ThubFlowStatusAction> readFlowStatusActions(Path basePath);

    Map<String, ThubFlowStatusTransition> readFlowStatusTransitions(Path basePath);

    Map<String, ThubFlowAssignment> readFlowAssignments(Path basePath);

    // ==================== Write Operations ====================

    void writeFlowTypes(Path basePath, Map<String, ThubFlowType> data);

    void writeFlowStatuses(Path basePath, Map<String, ThubFlowStatus> data);

    void writeFlowStatusActions(Path basePath, Map<String, ThubFlowStatusAction> data);

    void writeFlowStatusTransitions(Path basePath, Map<String, ThubFlowStatusTransition> data);

    void writeFlowAssignments(Path basePath, Map<String, ThubFlowAssignment> data);

    // ==================== Key Generation ====================

    static String flowTypeKey(String flowTypeId) {
        return "R_" + flowTypeId;
    }

    static String flowStatusKey(String statusId) {
        return "R_" + statusId;
    }

    static String flowStatusActionKey(String flowTypeId, String statusId) {
        return "R_" + flowTypeId + "_" + statusId;
    }

    static String flowStatusTransitionKey(String flowTypeId, String statusId, String nextStatusId) {
        return "R_" + flowTypeId + "_" + statusId + "_" + nextStatusId;
    }

    static String flowAssignmentKey(String assignmentId) {
        return "R_" + assignmentId;
    }
}
