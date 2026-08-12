package uz.xtreme.flowdesigner.controller;

import uz.xtreme.flowdesigner.config.GitProperties;
import uz.xtreme.flowdesigner.exception.FlowNotFoundException;
import uz.xtreme.flowdesigner.exception.FlowValidationException;
import uz.xtreme.flowdesigner.exception.WorkspaceNotFoundException;
import uz.xtreme.flowdesigner.service.flow.FlowService;
import uz.xtreme.flowdesigner.service.flow.dto.FlowSummary;
import uz.xtreme.flowdesigner.service.flow.dto.thub.ThubDeploymentData;
import uz.xtreme.flowdesigner.service.flow.dto.thub.ThubFlowStatus;
import uz.xtreme.flowdesigner.service.git.AuditInfo;
import uz.xtreme.flowdesigner.service.git.GitService;
import uz.xtreme.flowdesigner.service.git.WorkspaceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API controller for flow operations.
 * All flow data is now in THUB deployment format (ThubDeploymentData).
 */
@RestController
@RequestMapping("/api")
public class FlowController {

    private static final Logger log = LoggerFactory.getLogger(FlowController.class);

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_NAME_HEADER = "X-User-Name";
    private static final String USER_EMAIL_HEADER = "X-User-Email";
    private static final String BRANCH_HEADER = "X-Branch";
    private static final String DEFAULT_USER = "anonymous";
    private static final String DEFAULT_BRANCH = "main";

    private final FlowService flowService;
    private final GitService gitService;
    private final GitProperties gitProperties;

    public FlowController(FlowService flowService, GitService gitService, GitProperties gitProperties) {
        this.flowService = flowService;
        this.gitService = gitService;
        this.gitProperties = gitProperties;
    }

    // ==================== Config ====================

    @GetMapping("/config")
    public ConfigResponse getConfig() {
        return new ConfigResponse(gitProperties.defaultBranch());
    }

    // ==================== Branch Operations ====================

    @GetMapping("/branches")
    public List<String> listBranches() {
        log.debug("Listing remote branches");
        return gitService.listBranches();
    }

    // ==================== Main Branch Read Operations ====================

    @GetMapping("/flows")
    public List<FlowSummary> listFlows() {
        log.debug("Listing flows from main branch");
        return flowService.listFlowsFromMain();
    }

    @GetMapping("/statuses")
    public List<ThubFlowStatus> listStatuses() {
        log.debug("Listing all statuses from main branch");
        return flowService.getAllStatusesFromMain();
    }

    @GetMapping("/flows/{name}")
    public ThubDeploymentData getFlow(@PathVariable String name) {
        log.debug("Getting flow '{}' from main branch", name);
        return flowService.getFlowFromMain(name)
                .orElseThrow(() -> new FlowNotFoundException(name, "main"));
    }

    // ==================== Workspace Management ====================

    @PostMapping("/workspaces")
    public ResponseEntity<WorkspaceInfo> getOrCreateWorkspace(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestBody CreateWorkspaceRequest request) {

        String branchName = request.branchName() != null ? request.branchName() : DEFAULT_BRANCH;
        log.info("Getting/creating workspace for user '{}', branch '{}'", userId, branchName);

        List<String> errors = validateBranchName(branchName);
        if (!errors.isEmpty()) {
            throw new FlowValidationException(errors);
        }

        WorkspaceInfo workspace = gitService.getOrCreateWorkspace(userId, branchName);
        return ResponseEntity.status(HttpStatus.CREATED).body(workspace);
    }

    @GetMapping("/workspaces")
    public List<WorkspaceInfo> listWorkspaces(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId) {

        log.debug("Listing workspaces for user '{}'", userId);
        return gitService.getAllWorkspaces().stream()
                .filter(w -> w.userId().equals(userId))
                .toList();
    }

    @GetMapping("/workspaces/current")
    public WorkspaceInfo getCurrentWorkspace(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName) {

        log.debug("Getting workspace for user '{}', branch '{}'", userId, branchName);
        return gitService.getWorkspace(userId, branchName)
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        WorkspaceInfo.createId(userId, branchName)));
    }

    @DeleteMapping("/workspaces")
    public ResponseEntity<Void> deleteWorkspace(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName) {

        log.info("Deleting workspace for user '{}', branch '{}'", userId, branchName);
        gitService.cleanupWorkspace(userId, branchName);
        return ResponseEntity.noContent().build();
    }

    // ==================== Workspace Flow Operations ====================

    @GetMapping("/workspaces/flows")
    public List<FlowSummary> listWorkspaceFlows(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName) {

        log.debug("Listing flows in workspace for user '{}', branch '{}'", userId, branchName);
        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        return flowService.listFlows(workspace);
    }

    @GetMapping("/workspaces/statuses")
    public List<ThubFlowStatus> listWorkspaceStatuses(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName) {

        log.debug("Listing all statuses from workspace for user '{}', branch '{}'", userId, branchName);
        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        return flowService.getAllStatuses(workspace);
    }

    @GetMapping("/workspaces/flows/{name}")
    public ThubDeploymentData getWorkspaceFlow(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName,
            @PathVariable String name) {

        log.debug("Getting flow '{}' from workspace for user '{}', branch '{}'", name, userId, branchName);
        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        return flowService.getFlow(workspace, name)
                .orElseThrow(() -> new FlowNotFoundException(name, workspace.id()));
    }

    @PostMapping("/workspaces/flows")
    public ResponseEntity<FlowSummary> createFlow(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName,
            @RequestBody CreateFlowRequest request) {

        String flowTypeId = request.flowTypeId();
        log.info("Creating flow '{}' in workspace for user '{}', branch '{}'", flowTypeId, userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);

        if (flowService.flowExists(workspace, flowTypeId)) {
            throw new FlowValidationException("Flow with name '" + flowTypeId + "' already exists");
        }

        ThubDeploymentData deploymentData = request.deploymentData();
        flowService.saveFlow(workspace, flowTypeId, deploymentData);

        FlowSummary summary = FlowSummary.from(deploymentData.flowType());
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @PutMapping("/workspaces/flows/{name}")
    public FlowSummary updateFlow(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName,
            @PathVariable String name,
            @RequestBody UpdateFlowRequest request) {

        log.info("Updating flow '{}' in workspace for user '{}', branch '{}'", name, userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);

        if (!flowService.flowExists(workspace, name)) {
            throw new FlowNotFoundException(name, workspace.id());
        }

        ThubDeploymentData deploymentData = request.deploymentData();

        // Update lastModifiedBy on FlowType
        var updatedFlowType = deploymentData.flowType().withModification(userId);
        ThubDeploymentData updatedData = new ThubDeploymentData(
                updatedFlowType,
                deploymentData.flowStatuses(),
                deploymentData.flowStatusActions(),
                deploymentData.flowStatusTransitions(),
                deploymentData.flowAssignments()
        );

        flowService.saveFlow(workspace, name, updatedData);
        return FlowSummary.from(updatedFlowType);
    }

    @DeleteMapping("/workspaces/flows/{name}")
    public ResponseEntity<Void> deleteFlow(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName,
            @PathVariable String name) {

        log.info("Deleting flow '{}' from workspace for user '{}', branch '{}'", name, userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        boolean deleted = flowService.deleteFlow(workspace, name);

        if (!deleted) {
            throw new FlowNotFoundException(name, workspace.id());
        }

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/workspaces/flows/{name}/rename")
    public FlowSummary renameFlow(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName,
            @PathVariable String name,
            @RequestBody RenameFlowRequest request) {

        log.info("Renaming flow '{}' to '{}' in workspace for user '{}', branch '{}'",
                name, request.newName(), userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        flowService.renameFlow(workspace, name, request.newName());

        Optional<ThubDeploymentData> renamedFlow = flowService.getFlow(workspace, request.newName());
        return renamedFlow.map(d -> FlowSummary.from(d.flowType()))
                .orElse(new FlowSummary(request.newName(), request.newName(), null, "1.0", null, null));
    }

    @PostMapping("/flows/validate")
    public ValidationResponse validateFlow(@RequestBody ThubDeploymentData deploymentData) {
        log.debug("Validating flow data");
        List<String> errors = flowService.validateFlow(deploymentData);
        return new ValidationResponse(errors.isEmpty(), errors);
    }

    // ==================== Git Operations ====================

    @PostMapping("/workspaces/commit")
    public CommitResponse commitChanges(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = USER_NAME_HEADER, required = false) String userName,
            @RequestHeader(value = USER_EMAIL_HEADER, required = false) String userEmail,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName,
            @RequestBody CommitRequest request) {

        log.info("Committing changes in workspace for user '{}', branch '{}'", userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);

        String authorName = (userName != null && !userName.isBlank()) ? userName : userId;
        String authorEmail = (userEmail != null && !userEmail.isBlank()) ? userEmail : userId + "@flowdesigner.local";
        AuditInfo auditInfo = AuditInfo.of(userId, authorName, authorEmail);

        // Stage all changes in THUB directory
        gitService.add(workspace, "THUB/");

        String commitHash = gitService.commit(workspace, request.message(), auditInfo, null);
        return new CommitResponse(commitHash, request.message());
    }

    @PostMapping("/workspaces/push")
    public PushResponse pushChanges(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName) {

        log.info("Pushing changes from workspace for user '{}', branch '{}'", userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        gitService.push(workspace);

        try {
            gitService.pullMainRepo();
        } catch (Exception e) {
            log.warn("Failed to refresh main repository after push", e);
        }

        return new PushResponse(true, "Changes pushed successfully");
    }

    @PostMapping("/workspaces/pull")
    public PullResponse pullChanges(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName) {

        log.info("Pulling changes to workspace for user '{}', branch '{}'", userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        gitService.pull(workspace);

        return new PullResponse(true, "Changes pulled successfully");
    }

    @GetMapping("/workspaces/status")
    public Map<String, Object> getWorkspaceStatus(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName) {

        log.debug("Getting status for workspace user '{}', branch '{}'", userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        String currentVersion = gitService.getHeadCommit(workspace);

        return Map.of(
                "workspaceId", workspace.id(),
                "branch", workspace.branchName(),
                "currentVersion", currentVersion,
                "lastAccessed", workspace.lastAccessedAt()
        );
    }

    @PostMapping("/workspaces/branch")
    public ResponseEntity<WorkspaceInfo> createBranch(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String currentBranch,
            @RequestBody CreateBranchRequest request) {

        log.info("Creating branch '{}' from workspace for user '{}', current branch '{}'",
                request.newBranchName(), userId, currentBranch);

        List<String> errors = validateBranchName(request.newBranchName());
        if (!errors.isEmpty()) {
            throw new FlowValidationException(errors);
        }

        WorkspaceInfo currentWorkspace = getWorkspaceOrThrow(userId, currentBranch);
        gitService.createBranch(currentWorkspace, request.newBranchName());
        WorkspaceInfo newWorkspace = gitService.getOrCreateWorkspace(userId, request.newBranchName());

        return ResponseEntity.status(HttpStatus.CREATED).body(newWorkspace);
    }

    // ==================== Helper Methods ====================

    private WorkspaceInfo getWorkspaceOrThrow(String userId, String branchName) {
        return gitService.getWorkspace(userId, branchName)
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        WorkspaceInfo.createId(userId, branchName)));
    }

    private List<String> validateBranchName(String branchName) {
        if (branchName == null || branchName.isBlank()) {
            return List.of("Branch name cannot be empty");
        }
        if (!branchName.matches("^[a-zA-Z][a-zA-Z0-9/_-]*$")) {
            return List.of("Branch name must start with a letter and contain only letters, numbers, underscores, hyphens, and slashes");
        }
        if (branchName.length() > 200) {
            return List.of("Branch name cannot exceed 200 characters");
        }
        return List.of();
    }

    // ==================== Request/Response DTOs ====================

    public record CreateWorkspaceRequest(String branchName) {}

    public record CreateBranchRequest(String newBranchName) {}

    public record CreateFlowRequest(String flowTypeId, ThubDeploymentData deploymentData) {}

    public record UpdateFlowRequest(ThubDeploymentData deploymentData) {}

    public record RenameFlowRequest(String newName) {}

    public record CommitRequest(String message) {}

    public record ValidationResponse(boolean valid, List<String> errors) {}

    public record CommitResponse(String commitHash, String message) {}

    public record PushResponse(boolean success, String message) {}

    public record PullResponse(boolean success, String message) {}

    public record ConfigResponse(String defaultBranch) {}
}
