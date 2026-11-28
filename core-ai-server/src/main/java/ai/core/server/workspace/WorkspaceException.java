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
    public static final String REPLACED = "WORKSPACE_REPLACED";
    public static final String WAKING = "WORKSPACE_WAKING";
    public static final String WAKE_FAILED = "WORKSPACE_WAKE_FAILED";
    public static final String BOOTSTRAP_FAILED = "WORKSPACE_BOOTSTRAP_FAILED";
    public static final String START_FAILED = "WORKSPACE_START_FAILED";
    public static final String TASK_LOST = "WORKSPACE_TASK_LOST";
    public static final String VOLUME_UNAVAILABLE = "WORKSPACE_VOLUME_UNAVAILABLE";
    public static final String ARCHIVE_FAILED = "WORKSPACE_ARCHIVE_FAILED";
    public static final String ARCHIVE_NOT_FOUND = "WORKSPACE_ARCHIVE_NOT_FOUND";
    public static final String ARCHIVE_SHA256_MISMATCH = "WORKSPACE_ARCHIVE_SHA256_MISMATCH";
    public static final String ARCHIVE_GENERATION_MISMATCH = "WORKSPACE_ARCHIVE_GENERATION_MISMATCH";
    public static final String ARCHIVE_WORKSPACE_MISMATCH = "WORKSPACE_ARCHIVE_WORKSPACE_MISMATCH";
    public static final String RESTORE_TARGET_EXISTS = "WORKSPACE_RESTORE_TARGET_EXISTS";
    public static final String RESTORE_NOT_EMPTY = "WORKSPACE_RESTORE_NOT_EMPTY";
    public static final String UPGRADE_REQUIRES_ARCHIVE = "WORKSPACE_UPGRADE_REQUIRES_ARCHIVE";

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
