package ai.core.server.web;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.ResponseStatus;
import core.framework.http.ContentType;
import core.framework.http.HTTPMethod;
import core.framework.log.ErrorCode;
import core.framework.web.ErrorHandler;
import core.framework.web.Interceptor;
import core.framework.web.Invocation;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.MethodNotAllowedException;

import java.util.Optional;

/**
 * @author core-ai
 */
public class CorsInterceptor implements Interceptor, ErrorHandler {
    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        var path = request.path();

        if (!path.startsWith("/api/")) {
            return invocation.proceed();
        }

        var response = invocation.proceed();

        if (response.header("Access-Control-Allow-Origin").isEmpty()) {
            response.header("Access-Control-Allow-Origin", "*");
        }
        return response;
    }

    @Override
    public Optional<Response> handle(Request request, Throwable e) {
        // Handle OPTIONS preflight by catching MethodNotAllowedException
        if (request.method() == HTTPMethod.OPTIONS
                && request.path().startsWith("/api/")
                && e instanceof MethodNotAllowedException) {
            return Optional.of(preflightResponse());
        }
        // Error responses need CORS headers too: without Access-Control-Allow-Origin the browser
        // blocks cross-origin reads of 401/403/429 bodies, so clients cannot see errorCode/message
        // (e.g. QUOTA_EXCEEDED vs UNAUTHORIZED) and cannot decide whether to refresh the token.
        if (request.path().startsWith("/api/")) {
            String errorCode = e instanceof ErrorCode code ? code.errorCode() : "INTERNAL_ERROR";
            String message = e.getMessage() == null ? "" : e.getMessage();
            var json = "{\"errorCode\":\"" + escape(errorCode) + "\",\"message\":\"" + escape(message) + "\"}";
            return Optional.of(Response.text(json)
                    .status(httpStatus(e))
                    .contentType(ContentType.APPLICATION_JSON)
                    .header("Access-Control-Allow-Origin", "*"));
        }
        return Optional.empty();
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private HTTPStatus httpStatus(Throwable e) {
        ResponseStatus responseStatus = e.getClass().getDeclaredAnnotation(ResponseStatus.class);
        if (responseStatus != null) return responseStatus.value();
        return HTTPStatus.INTERNAL_SERVER_ERROR;
    }

    private Response preflightResponse() {
        return Response.empty()
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Auth-Request-Email, X-Auth-Request-User, x-trace-id")
                .header("Access-Control-Max-Age", "86400");
    }
}
