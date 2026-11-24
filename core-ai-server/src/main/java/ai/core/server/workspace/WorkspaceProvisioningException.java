package ai.core.server.workspace;

import core.framework.log.ErrorCode;

/**
 * Volume-level failure. Every code here maps to {@code workspaces.volume_status=ERROR}; runtime
 * failures (scheduling, mount, start script) deliberately do not use it, because they must keep
 * the volume READY and only mark the runtime failed.
 *
 * @author stephen
 */
public class WorkspaceProvisioningException extends RuntimeException implements ErrorCode {
    private static final long serialVersionUID = 1L;

    public static final String PROVISION_FAILED = "WORKSPACE_PROVISION_FAILED";
    public static final String VOLUME_UNAVAILABLE = "WORKSPACE_VOLUME_UNAVAILABLE";

    public final String errorCode;

    public WorkspaceProvisioningException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WorkspaceProvisioningException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    @Override
    public String errorCode() {
        return errorCode;
    }
}
