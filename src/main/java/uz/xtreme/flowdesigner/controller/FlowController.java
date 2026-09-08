package uz.xtreme.flowdesigner.controller;

import uz.xtreme.flowdesigner.config.GitProperties;
import uz.xtreme.flowdesigner.exception.FlowNotFoundException;
import uz.xtreme.flowdesigner.exception.FlowValidationException;
import uz.xtreme.flowdesigner.exception.WorkspaceNotFoundException;
import uz.xtreme.flowdesigner.service.flow.FlowService;
import uz.xtreme.flowdesigner.service.flow.dto.FlowLayout;
import uz.xtreme.flowdesigner.service.flow.dto.FlowSummary;
import uz.xtreme.flowdesigner.service.flow.dto.thub.ThubDeploymentData;
import uz.xtreme.flowdesigner.service.flow.dto.thub.ThubFlowStatus;
import uz.xtreme.flowdesigner.service.flow.dto.thub.ThubFlowType;
import uz.xtreme.flowdesigner.service.git.AuditInfo;
import uz.xtreme.flowdesigner.service.git.GitService;
import uz.xtreme.flowdesigner.service.git.WorkspaceInfo;
import uz.xtreme.flowdesigner.service.git.WorkspaceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
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

    @GetMapping("/flows/{name}/layout")
    public FlowLayout getFlowLayout(@PathVariable String name) {
        log.debug("Getting layout for flow '{}' from main branch", name);
        // An unknown flow answers the same way here as it does for the flow itself
        if (flowService.getFlowFromMain(name).isEmpty()) {
            throw new FlowNotFoundException(name, "main");
        }
        return flowService.getLayoutFromMain(name);
    }

    // ==================== Workspace Management ====================

    @PostMapping("/workspaces")
    public ResponseEntity<WorkspaceResponse> getOrCreateWorkspace(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestBody CreateWorkspaceRequest request) {

        String branchName = request.branchName() != null ? request.branchName() : DEFAULT_BRANCH;
        log.info("Getting/creating workspace for user '{}', branch '{}'", userId, branchName);

        List<String> errors = validateBranchName(branchName);
        if (!errors.isEmpty()) {
            throw new FlowValidationException(errors);
        }

        WorkspaceInfo workspace = gitService.getOrCreateWorkspace(userId, branchName);
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceResponse.from(workspace));
    }

    @GetMapping("/workspaces")
    public List<WorkspaceResponse> listWorkspaces(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId) {

        log.debug("Listing workspaces for user '{}'", userId);
        return gitService.getAllWorkspaces().stream()
                .filter(w -> w.userId().equals(userId))
                .map(WorkspaceResponse::from)
                .toList();
    }

    @GetMapping("/workspaces/current")
    public WorkspaceResponse getCurrentWorkspace(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName) {

        log.debug("Getting workspace for user '{}', branch '{}'", userId, branchName);
        return WorkspaceResponse.from(getWorkspaceOrThrow(userId, branchName));
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

    @GetMapping("/workspaces/flows/{name}/layout")
    public FlowLayout getWorkspaceFlowLayout(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName,
            @PathVariable String name) {

        log.debug("Getting layout for flow '{}' in workspace for user '{}'", name, userId);
        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        if (!flowService.flowExists(workspace, name)) {
            throw new FlowNotFoundException(name, workspace.id());
        }
        return flowService.getLayout(workspace, name);
    }

    @PutMapping("/workspaces/flows/{name}/layout")
    public FlowLayout saveWorkspaceFlowLayout(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName,
            @PathVariable String name,
            @RequestBody FlowLayout layout) {

        log.debug("Saving layout for flow '{}' in workspace for user '{}'", name, userId);
        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        flowService.saveLayout(workspace, name, layout);
        return layout;
    }

    @PostMapping("/workspaces/flows")
    public ResponseEntity<FlowSummary> createFlow(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName,
            @RequestBody CreateFlowRequest request) {

        String flowTypeId = request.flowTypeId();
        log.info("Creating flow '{}' in workspace for user '{}', branch '{}'", flowTypeId, userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);

        // Audit fields are the server's to set — never trust what the client sent.
        // createFlow checks for an existing flow under the same lock as the write,
        // so two concurrent creates cannot both find the name free.
        ThubDeploymentData requestData = request.deploymentData();
        ThubDeploymentData deploymentData = withCreationAudit(requestData, userId, flowTypeId);
        flowService.createFlow(workspace, flowTypeId, deploymentData);

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

        ThubDeploymentData stored = flowService.getFlow(workspace, name)
                .orElseThrow(() -> new FlowNotFoundException(name, workspace.id()));

        ThubDeploymentData deploymentData = request.deploymentData();
        if (deploymentData == null || deploymentData.flowType() == null) {
            throw new FlowValidationException("Flow type cannot be null");
        }

        // Creation audit comes from the stored record, never from the request: the
        // client rebuilds the flow type from its own canvas state and would blank
        // it out — or claim someone else wrote the flow
        var updatedFlowType = withStoredCreationAudit(deploymentData.flowType(), stored.flowType(), name)
                .withModification(userId);
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

        // Stage and commit as one unit so a concurrent save cannot slip into the
        // staged tree between the two steps
        String commitHash = gitService.withWorkspaceLock(workspace, () -> {
            gitService.addManagedFiles(workspace);
            return gitService.commit(workspace, request.message(), auditInfo, request.expectedVersion());
        });
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
    public WorkspaceStatusResponse getWorkspaceStatus(
            @RequestHeader(value = USER_ID_HEADER, defaultValue = DEFAULT_USER) String userId,
            @RequestHeader(value = BRANCH_HEADER, defaultValue = DEFAULT_BRANCH) String branchName) {

        log.debug("Getting status for workspace user '{}', branch '{}'", userId, branchName);

        WorkspaceInfo workspace = getWorkspaceOrThrow(userId, branchName);
        WorkspaceStatus status = gitService.getStatus(workspace);

        return new WorkspaceStatusResponse(
                workspace.id(),
                // The repository's own branch, not the registry's: if they ever
                // diverge, the client should see the truth
                status.branchName(),
                status.headCommit(),
                status.changedFiles(),
                status.unmanagedFiles(),
                status.clean(),
                status.aheadCount(),
                status.behindCount(),
                status.hasUpstream(),
                workspace.lastAccessedAt()
        );
    }

    @PostMapping("/workspaces/branch")
    public ResponseEntity<WorkspaceResponse> createBranch(
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
        gitService.createAndPushBranch(currentWorkspace, request.newBranchName());
        WorkspaceInfo newWorkspace = gitService.getOrCreateWorkspace(userId, request.newBranchName());

        return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceResponse.from(newWorkspace));
    }

    // ==================== Helper Methods ====================

    private WorkspaceInfo getWorkspaceOrThrow(String userId, String branchName) {
        return gitService.getWorkspace(userId, branchName)
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        WorkspaceInfo.createId(userId, branchName)));
    }

    /**
     * Restores createdBy/createdAt from the record already on disk, so an update
     * cannot rewrite — or erase — who first created the flow.
     */
    private ThubFlowType withStoredCreationAudit(ThubFlowType incoming, ThubFlowType stored, String flowTypeId) {
        if (stored == null) {
            return incoming;
        }
        return new ThubFlowType(
                // The record is keyed by the flow being saved; a body claiming a
                // different id would list under a name that then 404s
                flowTypeId,
                incoming.initialFlowStatusId(),
                incoming.finalFlowStatusId(),
                incoming.description(),
                incoming.version(),
                incoming.component(),
                stored.createdBy(),
                stored.createdAt(),
                incoming.lastModifiedBy(),
                incoming.lastModifiedAt(),
                incoming.categorization()
        );
    }

    /**
     * Stamps createdBy/createdAt (and the matching modification fields) from the
     * authenticated user, replacing whatever the client sent.
     */
    private ThubDeploymentData withCreationAudit(ThubDeploymentData data, String userId, String flowTypeId) {
        if (data == null || data.flowType() == null) {
            return data;
        }
        var flowType = data.flowType();
        Instant now = Instant.now();
        var stamped = new ThubFlowType(
                // Keyed by the requested flow name, never by what the body claims
                flowTypeId,
                flowType.initialFlowStatusId(),
                flowType.finalFlowStatusId(),
                flowType.description(),
                flowType.version(),
                flowType.component(),
                userId,
                now,
                userId,
                now,
                flowType.categorization()
        );
        return new ThubDeploymentData(
                stamped,
                data.flowStatuses(),
                data.flowStatusActions(),
                data.flowStatusTransitions(),
                data.flowAssignments()
        );
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

    /**
     * @param expectedVersion HEAD the client last saw; when present the commit is
     *                        rejected with 409 if the workspace moved on since.
     */
    public record CommitRequest(String message, String expectedVersion) {}

    public record ValidationResponse(boolean valid, List<String> errors) {}

    public record CommitResponse(String commitHash, String message) {}

    public record PushResponse(boolean success, String message) {}

    public record PullResponse(boolean success, String message) {}

    public record ConfigResponse(String defaultBranch) {}

    /**
     * Workspace as the client sees it. Deliberately without the on-disk path:
     * the server's filesystem layout is nothing the browser needs.
     */
    public record WorkspaceResponse(
            String id,
            String userId,
            String branchName,
            Instant createdAt,
            Instant lastAccessedAt
    ) {
        static WorkspaceResponse from(WorkspaceInfo workspace) {
            return new WorkspaceResponse(
                    workspace.id(),
                    workspace.userId(),
                    workspace.branchName(),
                    workspace.createdAt(),
                    workspace.lastAccessedAt());
        }
    }

    public record WorkspaceStatusResponse(
            String workspaceId,
            String branch,
            String currentVersion,
            List<String> changedFiles,
            List<String> unmanagedFiles,
            boolean clean,
            int aheadCount,
            int behindCount,
            boolean hasUpstream,
            Instant lastAccessed
    ) {}
}
