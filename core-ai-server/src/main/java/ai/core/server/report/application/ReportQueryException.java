package ai.core.server.report.application;

import core.framework.api.http.HTTPStatus;

/**
 * Query failure with a stable code that the web/tool layer can expose.
 *
 * @author core-ai
 */
public class ReportQueryException extends ReportException {
    private static final long serialVersionUID = 1L;

    private static HTTPStatus status(String code) {
        return switch (code) {
            case "INVALID_TIME_RANGE", "INVALID_SPEC" -> HTTPStatus.BAD_REQUEST;
            case "RESULT_LIMIT_EXCEEDED", "QUERY_BUDGET_EXCEEDED" -> HTTPStatus.UNPROCESSABLE_ENTITY;
            case "REPORT_QUERY_UNAVAILABLE" -> HTTPStatus.SERVICE_UNAVAILABLE;
            default -> HTTPStatus.BAD_REQUEST;
        };
    }

    public ReportQueryException(String code, String message) {
        super(code, status(code), message);
    }

    public ReportQueryException(String code, String message, Throwable cause) {
        super(code, status(code), message, cause);
    }
}
