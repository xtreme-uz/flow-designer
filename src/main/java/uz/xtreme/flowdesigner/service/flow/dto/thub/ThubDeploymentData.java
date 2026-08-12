package uz.xtreme.flowdesigner.service.flow.dto.thub;

import java.util.List;

/**
 * Complete THUB deployment data for a flow.
 * This is the format expected by the THUB deployment API.
 */
public record ThubDeploymentData(
        ThubFlowType flowType,
        List<ThubFlowStatus> flowStatuses,
        List<ThubFlowStatusAction> flowStatusActions,
        List<ThubFlowStatusTransition> flowStatusTransitions,
        List<ThubFlowAssignment> flowAssignments
) {
    public ThubDeploymentData {
        if (flowStatuses == null) {
            flowStatuses = List.of();
        }
        if (flowStatusActions == null) {
            flowStatusActions = List.of();
        }
        if (flowStatusTransitions == null) {
            flowStatusTransitions = List.of();
        }
        if (flowAssignments == null) {
            flowAssignments = List.of();
        }
    }
}
