package ai.core.flow.nodes;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.ResponseStatus;
import core.framework.log.ErrorCode;
import core.framework.log.Severity;

import java.io.Serial;

/**
 * @author stephen
 */
@ResponseStatus(HTTPStatus.CONFLICT)
public final class ThrowErrorFlowNodeException extends RuntimeException implements ErrorCode {
    @Serial
    private static final long serialVersionUID = 3252833572178108997L;

    private final String errorCode;

    public ThrowErrorFlowNodeException(String message) {
        super(message);
        errorCode = "THROW_ERROR_FLOW_NODE";
    }

    public ThrowErrorFlowNodeException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    public String errorCode() {
        return errorCode;
    }
}
