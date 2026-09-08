package uz.xtreme.flowdesigner.service.flow;

import uz.xtreme.flowdesigner.config.GitProperties;
import uz.xtreme.flowdesigner.exception.FlowNotFoundException;
import uz.xtreme.flowdesigner.exception.FlowValidationException;
import uz.xtreme.flowdesigner.exception.WorkspaceNotFoundException;
import uz.xtreme.flowdesigner.service.flow.dto.FlowSummary;
import uz.xtreme.flowdesigner.service.flow.dto.thub.*;
import uz.xtreme.flowdesigner.service.git.GitService;
import uz.xtreme.flowdesigner.service.git.WorkspaceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Implementation of FlowService using THUB configurator pattern.
 * Data is stored in shared THUB/ directory with 5 tables × 3 files each.
 */
@Service
public class FlowServiceImpl implements FlowService {

    private static final Logger log = LoggerFactory.getLogger(FlowServiceImpl.class);

    private static final Pattern FLOW_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]*$");
    /**
     * Deliberately permissive: these ids come from the shared FlowStatus file that
     * predates Flow Designer, so anything a THUB status is actually called has to
     * pass. It only rules out ids that could not be meant seriously — whitespace,
     * separators, and the canvas's own placeholder node ids.
     */
    private static final Pattern STATUS_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.\\-]*$");
    private static final Pattern CANVAS_NODE_ID_PATTERN = Pattern.compile("^node_\\d+$");
    private static final int MAX_FLOW_NAME_LENGTH = 100;

    private final GitProperties gitProperties;
    private final GitService gitService;
    private final ThubDataService thubDataService;

    public FlowServiceImpl(GitProperties gitProperties, GitService gitService, ThubDataService thubDataService) {
        this.gitProperties = gitProperties;
        this.gitService = gitService;
        this.thubDataService = thubDataService;
    }

    // ==================== Read Operations (Main Repository) ====================

    @Override
    public List<FlowSummary> listFlowsFromMain() {
        gitService.pullMainRepo();
        Path mainRepoPath = Path.of(gitProperties.mainRepoPath());
        return listFlowsFromPath(mainRepoPath);
    }

    @Override
    public Optional<ThubDeploymentData> getFlowFromMain(String flowTypeId) {
        gitService.pullMainRepo();
        Path mainRepoPath = Path.of(gitProperties.mainRepoPath());
        return getFlowFromPath(mainRepoPath, flowTypeId);
    }

    @Override
    public List<ThubFlowStatus> getAllStatusesFromMain() {
        gitService.pullMainRepo();
        Path mainRepoPath = Path.of(gitProperties.mainRepoPath());
        return thubDataService.readFlowStatuses(mainRepoPath).values().stream().toList();
    }

    // ==================== Read Operations (Workspace) ====================

    @Override
    public List<FlowSummary> listFlows(WorkspaceInfo workspace) {
        return listFlowsFromPath(workspace.path());
    }

    @Override
    public Optional<ThubDeploymentData> getFlow(WorkspaceInfo workspace, String flowTypeId) {
        return getFlowFromPath(workspace.path(), flowTypeId);
    }

    @Override
    public List<ThubFlowStatus> getAllStatuses(WorkspaceInfo workspace) {
        return thubDataService.readFlowStatuses(workspace.path()).values().stream().toList();
    }

    @Override
    public boolean flowExists(WorkspaceInfo workspace, String flowTypeId) {
        Map<String, ThubFlowType> flowTypes = thubDataService.readFlowTypes(workspace.path());
        return flowTypes.containsKey(ThubDataService.flowTypeKey(flowTypeId));
    }

    // ==================== Write Operations (Workspace Only) ====================

    @Override
    public void saveFlow(WorkspaceInfo workspace, String flowTypeId, ThubDeploymentData deploymentData) {
        // Five files are read and rewritten here; without the workspace lock a
        // concurrent save on the same workspace interleaves and loses records
        gitService.withWorkspaceLock(workspace, () -> saveFlowLocked(workspace, flowTypeId, deploymentData));
    }

    private void saveFlowLocked(WorkspaceInfo workspace, String flowTypeId, ThubDeploymentData deploymentData) {
        requireWorkspaceOnDisk(workspace);

        List<String> nameErrors = validateFlowName(flowTypeId);
        if (!nameErrors.isEmpty()) {
            throw new FlowValidationException(nameErrors);
        }

        List<String> dataErrors = validateFlow(deploymentData);
        if (!dataErrors.isEmpty()) {
            throw new FlowValidationException(dataErrors);
        }

        Path basePath = workspace.path();

        // Merge FlowType
        Map<String, ThubFlowType> flowTypes = thubDataService.readFlowTypes(basePath);
        flowTypes.put(ThubDataService.flowTypeKey(flowTypeId), deploymentData.flowType());
        thubDataService.writeFlowTypes(basePath, flowTypes);

        // Merge FlowStatuses (shared — add/update, don't remove others)
        Map<String, ThubFlowStatus> statuses = thubDataService.readFlowStatuses(basePath);
        for (ThubFlowStatus status : deploymentData.flowStatuses()) {
            statuses.put(ThubDataService.flowStatusKey(status.id()), status);
        }
        thubDataService.writeFlowStatuses(basePath, statuses);

        // Replace FlowStatusActions for this flowTypeId. Every record is stamped
        // with the flow being saved: the client may still be holding the name from
        // before a rename, and since removal now matches on this field, a stale one
        // would detach the record from its flow.
        Map<String, ThubFlowStatusAction> actions = thubDataService.readFlowStatusActions(basePath);
        removeByFlowTypeId(actions, flowTypeId, ThubFlowStatusAction::flowTypeId,
                a -> ThubDataService.flowStatusActionKey(flowTypeId, a.flowStatusId()));
        for (ThubFlowStatusAction action : deploymentData.flowStatusActions()) {
            String key = ThubDataService.flowStatusActionKey(flowTypeId, action.flowStatusId());
            actions.put(key, withFlowTypeId(action, flowTypeId));
        }
        thubDataService.writeFlowStatusActions(basePath, actions);

        // Replace FlowStatusTransitions for this flowTypeId
        Map<String, ThubFlowStatusTransition> transitions = thubDataService.readFlowStatusTransitions(basePath);
        removeByFlowTypeId(transitions, flowTypeId, ThubFlowStatusTransition::flowTypeId,
                t -> ThubDataService.flowStatusTransitionKey(flowTypeId, t.flowStatusId(), t.nextFlowStatusId()));
        for (ThubFlowStatusTransition transition : deploymentData.flowStatusTransitions()) {
            String key = ThubDataService.flowStatusTransitionKey(
                    flowTypeId, transition.flowStatusId(), transition.nextFlowStatusId());
            transitions.put(key, withFlowTypeId(transition, flowTypeId));
        }
        thubDataService.writeFlowStatusTransitions(basePath, transitions);

        // Replace FlowAssignments for this flowTypeId
        Map<String, ThubFlowAssignment> assignments = thubDataService.readFlowAssignments(basePath);
        removeByFlowTypeId(assignments, flowTypeId, ThubFlowAssignment::flowTypeId);
        for (ThubFlowAssignment assignment : deploymentData.flowAssignments()) {
            String key = ThubDataService.flowAssignmentKey(assignment.id());
            assignments.put(key, withFlowTypeId(assignment, flowTypeId));
        }
        thubDataService.writeFlowAssignments(basePath, assignments);

        log.info("Saved flow '{}' to workspace '{}'", flowTypeId, workspace.id());
    }

    @Override
    public boolean deleteFlow(WorkspaceInfo workspace, String flowTypeId) {
        return gitService.withWorkspaceLock(workspace, () -> deleteFlowLocked(workspace, flowTypeId));
    }

    private boolean deleteFlowLocked(WorkspaceInfo workspace, String flowTypeId) {
        requireWorkspaceOnDisk(workspace);
        Path basePath = workspace.path();
        String flowTypeKey = ThubDataService.flowTypeKey(flowTypeId);

        Map<String, ThubFlowType> flowTypes = thubDataService.readFlowTypes(basePath);
        if (!flowTypes.containsKey(flowTypeKey)) {
            return false;
        }

        // Remove FlowType record
        flowTypes.remove(flowTypeKey);
        thubDataService.writeFlowTypes(basePath, flowTypes);

        // Remove Actions for this flowTypeId
        Map<String, ThubFlowStatusAction> actions = thubDataService.readFlowStatusActions(basePath);
        removeByFlowTypeId(actions, flowTypeId, ThubFlowStatusAction::flowTypeId,
                a -> ThubDataService.flowStatusActionKey(flowTypeId, a.flowStatusId()));
        thubDataService.writeFlowStatusActions(basePath, actions);

        // Remove Transitions for this flowTypeId
        Map<String, ThubFlowStatusTransition> transitions = thubDataService.readFlowStatusTransitions(basePath);
        removeByFlowTypeId(transitions, flowTypeId, ThubFlowStatusTransition::flowTypeId,
                t -> ThubDataService.flowStatusTransitionKey(flowTypeId, t.flowStatusId(), t.nextFlowStatusId()));
        thubDataService.writeFlowStatusTransitions(basePath, transitions);

        // Remove Assignments for this flowTypeId
        Map<String, ThubFlowAssignment> assignments = thubDataService.readFlowAssignments(basePath);
        removeByFlowTypeId(assignments, flowTypeId, ThubFlowAssignment::flowTypeId);
        thubDataService.writeFlowAssignments(basePath, assignments);

        // Note: FlowStatuses are SHARED and NOT removed (they may be used by other flows)

        log.info("Deleted flow '{}' from workspace '{}' (statuses preserved)", flowTypeId, workspace.id());
        return true;
    }

    @Override
    public void renameFlow(WorkspaceInfo workspace, String oldFlowTypeId, String newFlowTypeId) {
        // Delete + re-create must be atomic against other writers on this workspace
        gitService.withWorkspaceLock(workspace, () -> renameFlowLocked(workspace, oldFlowTypeId, newFlowTypeId));
    }

    private void renameFlowLocked(WorkspaceInfo workspace, String oldFlowTypeId, String newFlowTypeId) {
        List<String> nameErrors = validateFlowName(newFlowTypeId);
        if (!nameErrors.isEmpty()) {
            throw new FlowValidationException(nameErrors);
        }

        if (!flowExists(workspace, oldFlowTypeId)) {
            throw new FlowNotFoundException(oldFlowTypeId, workspace.id());
        }

        if (flowExists(workspace, newFlowTypeId)) {
            throw new FlowValidationException("Flow with name '" + newFlowTypeId + "' already exists");
        }

        // Read old flow data
        Optional<ThubDeploymentData> oldData = getFlow(workspace, oldFlowTypeId);
        if (oldData.isEmpty()) {
            throw new FlowNotFoundException(oldFlowTypeId, workspace.id());
        }

        // Delete old records
        deleteFlow(workspace, oldFlowTypeId);

        // Re-create with new flowTypeId
        ThubDeploymentData data = oldData.get();
        ThubFlowType renamedFlowType = new ThubFlowType(
                newFlowTypeId,
                data.flowType().initialFlowStatusId(),
                data.flowType().finalFlowStatusId(),
                data.flowType().description(),
                data.flowType().version(),
                data.flowType().component(),
                data.flowType().createdBy(),
                data.flowType().createdAt(),
                data.flowType().lastModifiedBy(),
                data.flowType().lastModifiedAt(),
                data.flowType().categorization()
        );

        // Re-create actions with new flowTypeId
        List<ThubFlowStatusAction> renamedActions = data.flowStatusActions().stream()
                .map(a -> new ThubFlowStatusAction(
                        newFlowTypeId, a.flowStatusId(),
                        a.actionModuleId(), a.actionId(),
                        a.maxActionTriesCount(), a.maxActionTryingTime(), a.warningActionTryingTime()))
                .toList();

        // Re-create transitions with new flowTypeId
        List<ThubFlowStatusTransition> renamedTransitions = data.flowStatusTransitions().stream()
                .map(t -> new ThubFlowStatusTransition(
                        newFlowTypeId, t.flowStatusId(), t.nextFlowStatusId(),
                        t.actionResultTypeIds(), t.storeActionResultAsRequestResult()))
                .toList();

        // Re-create assignments with new flowTypeId
        List<ThubFlowAssignment> renamedAssignments = data.flowAssignments().stream()
                .map(a -> new ThubFlowAssignment(
                        a.id(), a.sourcePaymentActorId(), a.targetPaymentActorId(),
                        a.paymentType(), newFlowTypeId))
                .toList();

        ThubDeploymentData renamedData = new ThubDeploymentData(
                renamedFlowType, data.flowStatuses(),
                renamedActions, renamedTransitions, renamedAssignments
        );

        saveFlow(workspace, newFlowTypeId, renamedData);
        log.info("Renamed flow '{}' to '{}' in workspace '{}'", oldFlowTypeId, newFlowTypeId, workspace.id());
    }

    // ==================== Validation ====================

    @Override
    public List<String> validateFlow(ThubDeploymentData data) {
        List<String> errors = new ArrayList<>();

        if (data == null) {
            errors.add("Deployment data cannot be null");
            return errors;
        }

        if (data.flowType() == null) {
            errors.add("Flow type cannot be null");
            return errors;
        }

        if (data.flowType().id() == null || data.flowType().id().isBlank()) {
            errors.add("Flow type ID cannot be null or empty");
        }

        if (data.flowStatuses().isEmpty()) {
            errors.add("Flow must have at least one status");
        }

        // Check initial and final status exist
        String initialStatusId = data.flowType().initialFlowStatusId();
        String finalStatusId = data.flowType().finalFlowStatusId();

        if (initialStatusId == null || initialStatusId.isBlank()) {
            errors.add("Flow must have an initial status");
        }
        if (finalStatusId == null || finalStatusId.isBlank()) {
            errors.add("Flow must have a final status");
        }

        // Check that initial/final statuses exist in the status list
        Set<String> statusIds = new HashSet<>();
        for (ThubFlowStatus status : data.flowStatuses()) {
            if (status.id() == null || status.id().isBlank()) {
                errors.add("Status ID cannot be null or empty");
            } else if (CANVAS_NODE_ID_PATTERN.matcher(status.id()).matches()) {
                // FlowStatus records are shared by every flow, so an unnamed node
                // would put its internal canvas id in front of everyone else
                errors.add("Status '" + status.id() + "' has no status ID — open the node and give it one");
            } else if (!STATUS_ID_PATTERN.matcher(status.id()).matches()) {
                errors.add("Status ID '" + status.id() + "' may contain only letters, numbers, "
                        + "underscores, dots and hyphens");
            } else if (!statusIds.add(status.id())) {
                errors.add("Duplicate status ID: " + status.id());
            }
        }

        if (initialStatusId != null && !initialStatusId.isBlank() && !statusIds.contains(initialStatusId)) {
            errors.add("Initial status '" + initialStatusId + "' not found in flow statuses");
        }
        if (finalStatusId != null && !finalStatusId.isBlank() && !statusIds.contains(finalStatusId)) {
            errors.add("Final status '" + finalStatusId + "' not found in flow statuses");
        }

        // Validate transitions reference existing statuses
        Set<String> transitionPairs = new HashSet<>();
        for (ThubFlowStatusTransition transition : data.flowStatusTransitions()) {
            if (transition.flowStatusId() != null && !statusIds.contains(transition.flowStatusId())) {
                errors.add("Transition references non-existent status: " + transition.flowStatusId());
            }
            if (transition.nextFlowStatusId() != null && !statusIds.contains(transition.nextFlowStatusId())) {
                errors.add("Transition references non-existent next status: " + transition.nextFlowStatusId());
            }
            // A THUB transition is keyed by (flowType, status, nextStatus): a second
            // transition between the same pair would overwrite the first on write,
            // silently dropping one of the result types
            if (transition.flowStatusId() != null && transition.nextFlowStatusId() != null
                    && !transitionPairs.add(transition.flowStatusId() + " -> " + transition.nextFlowStatusId())) {
                errors.add("Duplicate transition '" + transition.flowStatusId() + " -> "
                        + transition.nextFlowStatusId() + "' — list every result type on a single transition");
            }
        }

        // Validate actions reference existing statuses
        for (ThubFlowStatusAction action : data.flowStatusActions()) {
            if (action.flowStatusId() != null && !statusIds.contains(action.flowStatusId())) {
                errors.add("Action references non-existent status: " + action.flowStatusId());
            }
        }

        return errors;
    }

    @Override
    public List<String> validateFlowName(String flowName) {
        List<String> errors = new ArrayList<>();

        if (flowName == null || flowName.isBlank()) {
            errors.add("Flow name cannot be null or empty");
            return errors;
        }

        if (flowName.length() > MAX_FLOW_NAME_LENGTH) {
            errors.add("Flow name cannot exceed " + MAX_FLOW_NAME_LENGTH + " characters");
        }

        if (!FLOW_NAME_PATTERN.matcher(flowName).matches()) {
            errors.add("Flow name must start with a letter and contain only letters, numbers, underscores, and hyphens");
        }

        return errors;
    }

    // ==================== Private Helper Methods ====================

    /**
     * Fails a write whose workspace is no longer a git clone. Idle cleanup may
     * have removed it between the request arriving and the lock being taken;
     * writing anyway would recreate a bare THUB/ directory outside any
     * repository and report success while the data is unreachable.
     */
    private void requireWorkspaceOnDisk(WorkspaceInfo workspace) {
        if (!java.nio.file.Files.isDirectory(workspace.path().resolve(".git"))) {
            throw new WorkspaceNotFoundException(workspace.id());
        }
    }

    private List<FlowSummary> listFlowsFromPath(Path basePath) {
        Map<String, ThubFlowType> flowTypes = thubDataService.readFlowTypes(basePath);
        return flowTypes.values().stream()
                .map(FlowSummary::from)
                .sorted(Comparator.comparing(FlowSummary::name))
                .toList();
    }

    private Optional<ThubDeploymentData> getFlowFromPath(Path basePath, String flowTypeId) {
        Map<String, ThubFlowType> flowTypes = thubDataService.readFlowTypes(basePath);
        ThubFlowType flowType = flowTypes.get(ThubDataService.flowTypeKey(flowTypeId));
        if (flowType == null) {
            return Optional.empty();
        }

        // Read all shared data and filter by flowTypeId
        Map<String, ThubFlowStatus> allStatuses = thubDataService.readFlowStatuses(basePath);
        Map<String, ThubFlowStatusAction> allActions = thubDataService.readFlowStatusActions(basePath);
        Map<String, ThubFlowStatusTransition> allTransitions = thubDataService.readFlowStatusTransitions(basePath);
        Map<String, ThubFlowAssignment> allAssignments = thubDataService.readFlowAssignments(basePath);

        // Filter actions by flowTypeId
        List<ThubFlowStatusAction> actions = allActions.values().stream()
                .filter(a -> flowTypeId.equals(a.flowTypeId()))
                .toList();

        // Filter transitions by flowTypeId
        List<ThubFlowStatusTransition> transitions = allTransitions.values().stream()
                .filter(t -> flowTypeId.equals(t.flowTypeId()))
                .toList();

        // Filter assignments by flowTypeId
        List<ThubFlowAssignment> assignments = allAssignments.values().stream()
                .filter(a -> flowTypeId.equals(a.flowTypeId()))
                .toList();

        // Collect status IDs used by this flow
        Set<String> usedStatusIds = new HashSet<>();
        if (flowType.initialFlowStatusId() != null) usedStatusIds.add(flowType.initialFlowStatusId());
        if (flowType.finalFlowStatusId() != null) usedStatusIds.add(flowType.finalFlowStatusId());
        actions.forEach(a -> { if (a.flowStatusId() != null) usedStatusIds.add(a.flowStatusId()); });
        transitions.forEach(t -> {
            if (t.flowStatusId() != null) usedStatusIds.add(t.flowStatusId());
            if (t.nextFlowStatusId() != null) usedStatusIds.add(t.nextFlowStatusId());
        });

        // Get only the statuses used by this flow
        List<ThubFlowStatus> statuses = allStatuses.values().stream()
                .filter(s -> usedStatusIds.contains(s.id()))
                .toList();

        return Optional.of(new ThubDeploymentData(flowType, statuses, actions, transitions, assignments));
    }

    /**
     * Removes every record owned by the given flow, matching on the record's own
     * {@code flowtypeid} field.
     *
     * <p>Matching on the R_ key prefix instead would delete records of a different
     * flow whose name happens to extend this one: flow names may contain '_', so
     * the key {@code R_payment_reversal_ACCEPTED} (flow "payment_reversal") starts
     * with the prefix {@code R_payment_} of the flow "payment". The field is
     * unambiguous, and it also covers FlowAssignment, whose key
     * ({@code R_{assignmentId}}) carries no flow name at all.
     */
    private static ThubFlowStatusAction withFlowTypeId(ThubFlowStatusAction action, String flowTypeId) {
        if (flowTypeId.equals(action.flowTypeId())) {
            return action;
        }
        return new ThubFlowStatusAction(
                flowTypeId, action.flowStatusId(), action.actionModuleId(), action.actionId(),
                action.maxActionTriesCount(), action.maxActionTryingTime(), action.warningActionTryingTime());
    }

    private static ThubFlowStatusTransition withFlowTypeId(ThubFlowStatusTransition transition, String flowTypeId) {
        if (flowTypeId.equals(transition.flowTypeId())) {
            return transition;
        }
        return new ThubFlowStatusTransition(
                flowTypeId, transition.flowStatusId(), transition.nextFlowStatusId(),
                transition.actionResultTypeIds(), transition.storeActionResultAsRequestResult());
    }

    private static ThubFlowAssignment withFlowTypeId(ThubFlowAssignment assignment, String flowTypeId) {
        if (flowTypeId.equals(assignment.flowTypeId())) {
            return assignment;
        }
        return new ThubFlowAssignment(
                assignment.id(), assignment.sourcePaymentActorId(), assignment.targetPaymentActorId(),
                assignment.paymentType(), flowTypeId);
    }

    private static <T> void removeByFlowTypeId(Map<String, T> map, String flowTypeId,
                                               Function<T, String> flowTypeIdExtractor) {
        removeByFlowTypeId(map, flowTypeId, flowTypeIdExtractor, null);
    }

    /**
     * @param keyBuilder key this flow would store the record under, used to claim
     *                   records written before the flowtypeid field was filled in;
     *                   without it such a record can never be replaced or deleted
     *                   and lingers in the shared file forever
     */
    private static <T> void removeByFlowTypeId(Map<String, T> map, String flowTypeId,
                                               Function<T, String> flowTypeIdExtractor,
                                               Function<T, String> keyBuilder) {
        map.entrySet().removeIf(entry -> {
            String recordFlowTypeId = flowTypeIdExtractor.apply(entry.getValue());
            if (recordFlowTypeId != null) {
                return flowTypeId.equals(recordFlowTypeId);
            }
            return keyBuilder != null && entry.getKey().equals(keyBuilder.apply(entry.getValue()));
        });
    }
}
