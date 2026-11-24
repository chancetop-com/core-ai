package ai.core.server.workspace;

import core.framework.log.ErrorCode;

/**
 * User-visible workspace failure. The code is part of the API contract (the frontend switches on
 * it), so it is kept separate from {@link WorkspaceProvisioningException}, which only describes
 * volume-level faults.
 *
 * @author stephen
 */
public class WorkspaceException extends RuntimeException implements ErrorCode {
    private static final long serialVersionUID = 1L;

    public static final String UNSUPPORTED = "WORKSPACE_UNSUPPORTED";
    public static final String INVALID_REQUEST = "WORKSPACE_INVALID_REQUEST";
    public static final String NOT_FOUND = "WORKSPACE_NOT_FOUND";
    public static final String DELETED = "WORKSPACE_DELETED";
    public static final String NAME_CONFLICT = "WORKSPACE_NAME_CONFLICT";
    public static final String QUOTA_EXCEEDED = "WORKSPACE_QUOTA_EXCEEDED";
    public static final String BUSY = "WORKSPACE_BUSY";
    public static final String WAKING = "WORKSPACE_WAKING";
    public static final String WAKE_FAILED = "WORKSPACE_WAKE_FAILED";
    public static final String TASK_LOST = "WORKSPACE_TASK_LOST";
    public static final String VOLUME_UNAVAILABLE = "WORKSPACE_VOLUME_UNAVAILABLE";

    public final String errorCode;

    public WorkspaceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WorkspaceException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    @Override
    public String errorCode() {
        return errorCode;
    }
}
