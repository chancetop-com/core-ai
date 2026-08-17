package ai.core.server.web;

import core.framework.api.http.HTTPStatus;
import core.framework.http.HTTPMethod;
import core.framework.web.Request;
import core.framework.web.exception.MethodNotAllowedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorsInterceptorTest {
    private final CorsInterceptor interceptor = new CorsInterceptor();

    @Test
    void answersHeadProbeOnGatewayRoutes() {
        var response = interceptor.handle(request(HTTPMethod.HEAD, "/api/gateway/v1/chat/completions"),
                new MethodNotAllowedException("method not allowed, method=HEAD"));

        assertTrue(response.isPresent());
        assertEquals(HTTPStatus.OK, response.orElseThrow().status());
    }

    @Test
    void leavesHeadOnOtherRoutesToDefaultErrorHandling() {
        var response = interceptor.handle(request(HTTPMethod.HEAD, "/api/agents"),
                new MethodNotAllowedException("method not allowed, method=HEAD"));

        assertTrue(response.isPresent());
        assertEquals(HTTPStatus.METHOD_NOT_ALLOWED, response.orElseThrow().status());
    }

    @Test
    void leavesOtherExceptionsOnGatewayRoutesToDefaultErrorHandling() {
        var response = interceptor.handle(request(HTTPMethod.HEAD, "/api/gateway/v1/chat/completions"),
                new RuntimeException("boom"));

        assertTrue(response.isPresent());
        assertEquals(HTTPStatus.INTERNAL_SERVER_ERROR, response.orElseThrow().status());
    }

    @Test
    void answersOptionsPreflight() {
        var response = interceptor.handle(request(HTTPMethod.OPTIONS, "/api/agents"),
                new MethodNotAllowedException("method not allowed, method=OPTIONS"));

        assertTrue(response.isPresent());
        assertEquals(HTTPStatus.NO_CONTENT, response.orElseThrow().status());
    }

    private Request request(HTTPMethod method, String path) {
        var request = mock(Request.class);
        when(request.method()).thenReturn(method);
        when(request.path()).thenReturn(path);
        return request;
    }
}
