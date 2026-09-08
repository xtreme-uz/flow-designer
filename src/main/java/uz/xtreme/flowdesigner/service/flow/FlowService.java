package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.service.flow.dto.FlowLayout;
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

    /**
     * Saves a flow that must not exist yet. The existence check runs under the
     * workspace lock together with the write, so two concurrent creates of the
     * same name cannot both pass it and overwrite each other.
     *
     * @throws uz.xtreme.flowdesigner.exception.FlowValidationException if the flow already exists
     */
    void createFlow(WorkspaceInfo workspace, String flowTypeId, ThubDeploymentData deploymentData);

    /**
     * Canvas layout of a flow in the workspace, empty when it has none.
     */
    FlowLayout getLayout(WorkspaceInfo workspace, String flowTypeId);

    /**
     * Canvas layout of a flow on the main branch, empty when it has none.
     */
    FlowLayout getLayoutFromMain(String flowTypeId);

    /**
     * Stores the canvas layout of a flow. Also records which statuses the flow is
     * made of, so one that is not referenced by any action or transition still
     * comes back the next time the flow is opened.
     */
    void saveLayout(WorkspaceInfo workspace, String flowTypeId, FlowLayout layout);

    boolean deleteFlow(WorkspaceInfo workspace, String flowTypeId);

    void renameFlow(WorkspaceInfo workspace, String oldFlowTypeId, String newFlowTypeId);

    // ==================== Validation ====================

    List<String> validateFlow(ThubDeploymentData deploymentData);

    List<String> validateFlowName(String flowName);
}
