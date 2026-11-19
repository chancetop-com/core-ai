package ai.core.server.report.application;

import ai.core.server.web.TypedWebError;
import core.framework.api.http.HTTPStatus;
import core.framework.log.ErrorCode;

/**
 * Report-domain failure with a stable error code and HTTP status.
 *
 * @author core-ai
 */
public class ReportException extends RuntimeException implements ErrorCode, TypedWebError {
    private static final long serialVersionUID = 1L;

    public static ReportException notFound() {
        return new ReportException("REPORT_NOT_FOUND", HTTPStatus.NOT_FOUND, "report not found");
    }

    public static ReportException conflict(String code, String message) {
        return new ReportException(code, HTTPStatus.CONFLICT, message);
    }

    public static ReportException forbidden(String code, String message) {
        return new ReportException(code, HTTPStatus.FORBIDDEN, message);
    }

    public static ReportException badRequest(String code, String message) {
        return new ReportException(code, HTTPStatus.BAD_REQUEST, message);
    }

    public static ReportException badRequest(String code, String message, Throwable cause) {
        return new ReportException(code, HTTPStatus.BAD_REQUEST, message, cause);
    }

    public static ReportException limit(String code, String message) {
        return new ReportException(code, HTTPStatus.CONFLICT, message);
    }

    public static ReportException unavailable(String code, String message) {
        return new ReportException(code, HTTPStatus.SERVICE_UNAVAILABLE, message);
    }

    public static ReportException rateLimited(String message) {
        return new ReportException("RATE_LIMITED", HTTPStatus.TOO_MANY_REQUESTS, message, null, 60);
    }

    private final String errorCode;
    private final HTTPStatus status;
    private final String extraJson;
    private final Integer retryAfterSeconds;

    public ReportException(String errorCode, HTTPStatus status, String message) {
        this(errorCode, status, message, null, null);
    }

    public ReportException(String errorCode, HTTPStatus status, String message, String extraJson) {
        this(errorCode, status, message, extraJson, null);
    }

    public ReportException(String errorCode, HTTPStatus status, String message, String extraJson, Integer retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.extraJson = extraJson;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ReportException(String errorCode, HTTPStatus status, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.status = status;
        this.extraJson = null;
        this.retryAfterSeconds = null;
    }

    @Override
    public String errorCode() {
        return errorCode;
    }

    @Override
    public HTTPStatus status() {
        return status;
    }

    @Override
    public String extraJson() {
        return extraJson;
    }

    @Override
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
