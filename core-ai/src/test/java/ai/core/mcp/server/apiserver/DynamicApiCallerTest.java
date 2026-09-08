package ai.core.mcp.server.apiserver;

import ai.core.api.apidefinition.ApiDefinition;
import ai.core.api.apidefinition.ApiDefinitionType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Xander
 */
class DynamicApiCallerTest {

    @Test
    void blankQueryParamIsOmittedFromRequest() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var receivedQuery = new AtomicReference<String>();
        server.createContext("/seo/organic-keyword-rank", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var address = InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
            var caller = new DynamicApiCaller(List.of(buildSeoApi("http://" + address)));

            var response = caller.callApiWithRsp("test_app_SeoService_searchOrganicKeywordRank",
                    "{\"site\":\"\",\"date\":\"2026-05-24\"}");

            assertNotNull(response);
            assertEquals(200, response.statusCode);
            // an empty query param value must be omitted, not crash Collectors.toMap with a NullPointerException
            assertEquals("date=2026-05-24", receivedQuery.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transportFailureThrowsInsteadOfReturningFake500() {
        var caller = new DynamicApiCaller(List.of(buildSeoApi("http://127.0.0.1:1"))); // unreachable on purpose

        var exception = assertThrows(IllegalStateException.class,
                () -> caller.callApiWithRsp("test_app_SeoService_searchOrganicKeywordRank", "{\"site\":\"\",\"date\":\"2026-05-24\"}"));

        // transport failures propagate to the caller instead of being masked as a 500 text body
        assertTrue(exception.getMessage().contains("Call api["));
        assertNotNull(exception.getCause());
    }

    private ApiDefinition buildSeoApi(String baseUrl) {
        var api = new ApiDefinition();
        api.app = "test-app";
        api.baseUrl = baseUrl;
        api.version = "1";

        var operation = new ApiDefinition.Operation();
        operation.name = "searchOrganicKeywordRank";
        operation.method = "GET";
        operation.path = "/seo/organic-keyword-rank";
        operation.requestType = "SearchRequest";

        var service = new ApiDefinition.Service();
        service.name = "SeoService";
        service.operations = List.of(operation);
        api.services = List.of(service);

        var type = new ApiDefinitionType();
        type.name = "SearchRequest";
        type.type = "bean";
        type.fields = List.of(stringField("site"), stringField("date"));
        api.types = List.of(type);
        return api;
    }

    private ApiDefinitionType.Field stringField(String name) {
        var field = new ApiDefinitionType.Field();
        field.name = name;
        field.type = "String";
        return field;
    }
}
