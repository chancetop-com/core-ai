package ai.core.mcp.server.apiserver;

import ai.core.api.apidefinition.ApiDefinition;
import ai.core.api.apidefinition.ApiDefinitionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author Xander
 */
class DynamicApiCallerTest {

    @Test
    void testGetOperationOmitsBlankQueryParamInsteadOfThrowing() {
        var caller = new DynamicApiCaller(List.of(buildSeoApi()));

        // an empty query param value must be omitted, not crash Collectors.toMap with a NullPointerException
        assertDoesNotThrow(() -> {
            var response = caller.callApi("test_app_SeoService_searchOrganicKeywordRank", "{\"site\":\"\",\"date\":\"2026-05-24\"}");
            assertNotNull(response);
        });
    }

    private ApiDefinition buildSeoApi() {
        var api = new ApiDefinition();
        api.app = "test-app";
        api.baseUrl = "http://127.0.0.1:1"; // unreachable on purpose: the HTTP call fails fast and is handled, no external dependency
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
