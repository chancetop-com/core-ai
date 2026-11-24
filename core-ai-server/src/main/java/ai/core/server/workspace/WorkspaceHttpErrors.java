package ai.core.server.workspace;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.ResponseStatus;
import core.framework.log.Severity;

/**
 * Maps the domain {@link WorkspaceException} codes onto HTTP statuses. core-ng reads
 * {@code @ResponseStatus} only from the concrete exception class, so one class per status is needed.
 *
 * @author stephen
 */
public final class WorkspaceHttpErrors {

    public static RuntimeException translate(WorkspaceException e) {
        String code = e.errorCode();
        return switch (code) {
            case WorkspaceException.DELETED, "WORKSPACE_TASK_LOST" -> new Gone(code, e.getMessage(), e);
            case WorkspaceException.QUOTA_EXCEEDED -> new QuotaExceeded(code, e.getMessage(), e);
            case WorkspaceException.UNSUPPORTED, WorkspaceException.VOLUME_UNAVAILABLE -> new Unavailable(code, e.getMessage(), e);
            case WorkspaceException.NOT_FOUND -> new NotFound(code, e.getMessage(), e);
            case WorkspaceException.INVALID_REQUEST -> new Invalid(code, e.getMessage(), e);
            default -> new Conflict(code, e.getMessage(), e);
        };
    }

    private WorkspaceHttpErrors() {
    }

    @ResponseStatus(HTTPStatus.GONE)
    public static final class Gone extends WorkspaceException {
        private static final long serialVersionUID = 1L;

        Gone(String errorCode, String message, Throwable cause) {
            super(errorCode, message, cause);
        }

        @Override
        public Severity severity() {
            return Severity.WARN;
        }
    }

    @ResponseStatus(HTTPStatus.TOO_MANY_REQUESTS)
    public static final class QuotaExceeded extends WorkspaceException {
        private static final long serialVersionUID = 1L;

        QuotaExceeded(String errorCode, String message, Throwable cause) {
            super(errorCode, message, cause);
        }

        @Override
        public Severity severity() {
            return Severity.WARN;
        }
    }

    @ResponseStatus(HTTPStatus.SERVICE_UNAVAILABLE)
    public static final class Unavailable extends WorkspaceException {
        private static final long serialVersionUID = 1L;

        Unavailable(String errorCode, String message, Throwable cause) {
            super(errorCode, message, cause);
        }
    }

    @ResponseStatus(HTTPStatus.NOT_FOUND)
    public static final class NotFound extends WorkspaceException {
        private static final long serialVersionUID = 1L;

        NotFound(String errorCode, String message, Throwable cause) {
            super(errorCode, message, cause);
        }

        @Override
        public Severity severity() {
            return Severity.WARN;
        }
    }

    @ResponseStatus(HTTPStatus.BAD_REQUEST)
    public static final class Invalid extends WorkspaceException {
        private static final long serialVersionUID = 1L;

        Invalid(String errorCode, String message, Throwable cause) {
            super(errorCode, message, cause);
        }

        @Override
        public Severity severity() {
            return Severity.WARN;
        }
    }

    @ResponseStatus(HTTPStatus.CONFLICT)
    public static final class Conflict extends WorkspaceException {
        private static final long serialVersionUID = 1L;

        Conflict(String errorCode, String message, Throwable cause) {
            super(errorCode, message, cause);
        }

        @Override
        public Severity severity() {
            return Severity.WARN;
        }
    }

}
