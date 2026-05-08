package ai.core.server.web;

import core.framework.http.HTTPMethod;
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
        return Optional.empty();
    }

    private Response preflightResponse() {
        return Response.empty()
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Auth-Request-Email, X-Auth-Request-User, x-trace-id")
                .header("Access-Control-Max-Age", "86400");
    }
}
