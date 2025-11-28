package ai.core;

import core.framework.log.ErrorCode;

/**
 * @author stephen
 */
public class AgentRuntimeException extends RuntimeException implements ErrorCode {
    private final String errorCode;

    public AgentRuntimeException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    @Override
    public String errorCode() {
        return errorCode;
    }
}
