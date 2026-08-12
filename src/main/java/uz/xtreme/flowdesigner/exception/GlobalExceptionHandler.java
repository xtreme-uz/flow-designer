package uz.xtreme.flowdesigner.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * Global exception handler for REST API errors.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GitVersionConflictException.class)
    public ProblemDetail handleVersionConflict(GitVersionConflictException ex) {
        log.warn("Version conflict: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage());
        problem.setTitle("Version Conflict");
        problem.setType(URI.create("https://api.flowdesigner.com/errors/version-conflict"));
        problem.setProperty("expectedVersion", ex.getExpectedVersion());
        problem.setProperty("actualVersion", ex.getActualVersion());
        problem.setProperty("workspaceId", ex.getWorkspaceId());
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ProblemDetail handleWorkspaceNotFound(WorkspaceNotFoundException ex) {
        log.warn("Workspace not found: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage());
        problem.setTitle("Workspace Not Found");
        problem.setType(URI.create("https://api.flowdesigner.com/errors/workspace-not-found"));
        problem.setProperty("workspaceId", ex.getWorkspaceId());
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    @ExceptionHandler(GitAuthenticationException.class)
    public ProblemDetail handleAuthenticationFailure(GitAuthenticationException ex) {
        log.error("Git authentication failed: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Git authentication failed");
        problem.setTitle("Authentication Failed");
        problem.setType(URI.create("https://api.flowdesigner.com/errors/authentication-failed"));
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    @ExceptionHandler(GitOperationException.class)
    public ProblemDetail handleGitOperation(GitOperationException ex) {
        log.error("Git operation failed: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage());
        problem.setTitle("Git Operation Failed");
        problem.setType(URI.create("https://api.flowdesigner.com/errors/git-operation-failed"));
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    @ExceptionHandler(FlowNotFoundException.class)
    public ProblemDetail handleFlowNotFound(FlowNotFoundException ex) {
        log.warn("Flow not found: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage());
        problem.setTitle("Flow Not Found");
        problem.setType(URI.create("https://api.flowdesigner.com/errors/flow-not-found"));
        problem.setProperty("flowName", ex.getFlowName());
        if (ex.getLocation() != null) {
            problem.setProperty("location", ex.getLocation());
        }
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    @ExceptionHandler(FlowValidationException.class)
    public ProblemDetail handleFlowValidation(FlowValidationException ex) {
        log.warn("Flow validation failed: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage());
        problem.setTitle("Flow Validation Failed");
        problem.setType(URI.create("https://api.flowdesigner.com/errors/flow-validation-failed"));
        problem.setProperty("errors", ex.getErrors());
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }

    @ExceptionHandler(FlowStorageException.class)
    public ProblemDetail handleFlowStorage(FlowStorageException ex) {
        log.error("Flow storage operation failed: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage());
        problem.setTitle("Flow Storage Failed");
        problem.setType(URI.create("https://api.flowdesigner.com/errors/flow-storage-failed"));
        problem.setProperty("timestamp", Instant.now());

        return problem;
    }
}
