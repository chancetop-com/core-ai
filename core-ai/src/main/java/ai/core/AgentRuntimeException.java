package ai.core;

import core.framework.log.ErrorCode;

import java.io.Serial;

/**
 * @author stephen
 */
public class AgentRuntimeException extends RuntimeException implements ErrorCode {
    @Serial
    private static final long serialVersionUID = 8426481236734448674L;

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
