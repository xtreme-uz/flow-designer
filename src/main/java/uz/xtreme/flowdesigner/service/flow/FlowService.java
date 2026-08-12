package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.service.flow.dto.FlowSummary;
import uz.xtreme.flowdesigner.service.flow.dto.thub.ThubDeploymentData;
import uz.xtreme.flowdesigner.service.flow.dto.thub.ThubFlowStatus;
import uz.xtreme.flowdesigner.service.git.WorkspaceInfo;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for managing flows.
 * Flows are stored in THUB configurator pattern (shared data files).
 */
public interface FlowService {

    // ==================== Read Operations (Main Repository) ====================

    List<FlowSummary> listFlowsFromMain();

    Optional<ThubDeploymentData> getFlowFromMain(String flowTypeId);

    List<ThubFlowStatus> getAllStatusesFromMain();

    // ==================== Read Operations (Workspace) ====================

    List<FlowSummary> listFlows(WorkspaceInfo workspace);

    Optional<ThubDeploymentData> getFlow(WorkspaceInfo workspace, String flowTypeId);

    List<ThubFlowStatus> getAllStatuses(WorkspaceInfo workspace);

    boolean flowExists(WorkspaceInfo workspace, String flowTypeId);

    // ==================== Write Operations (Workspace Only) ====================

    void saveFlow(WorkspaceInfo workspace, String flowTypeId, ThubDeploymentData deploymentData);

    boolean deleteFlow(WorkspaceInfo workspace, String flowTypeId);

    void renameFlow(WorkspaceInfo workspace, String oldFlowTypeId, String newFlowTypeId);

    // ==================== Validation ====================

    List<String> validateFlow(ThubDeploymentData deploymentData);

    List<String> validateFlowName(String flowName);
}
