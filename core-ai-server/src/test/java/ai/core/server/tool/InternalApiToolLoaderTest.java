package ai.core.server.tool;

import ai.core.api.apidefinition.ApiDefinition;
import ai.core.api.apidefinition.ApiDefinitionType;
import ai.core.server.apimcp.serviceapi.service.ApiDefinitionService;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Xander
 */
class InternalApiToolLoaderTest {
    private static ApiDefinitionType.Field field(String name, String type, boolean notNull) {
        var field = new ApiDefinitionType.Field();
        field.name = name;
        field.type = type;
        field.constraints.notNull = notNull;
        return field;
    }

    private static ApiDefinitionType bean(String name, ApiDefinitionType.Field... fields) {
        var type = new ApiDefinitionType();
        type.name = name;
        type.type = "bean";
        type.fields = List.of(fields);
        return type;
    }

    private static ApiDefinition.PathParam pathParam(String name, String type) {
        var param = new ApiDefinition.PathParam();
        param.name = name;
        param.type = type;
        return param;
    }

    private static ApiDefinition buildApi() {
        var api = new ApiDefinition();
        api.app = "test-app";
        api.baseUrl = "http://127.0.0.1";
        api.version = "1";

        var operation = new ApiDefinition.Operation();
        operation.name = "getUser";
        operation.description = "Get a user";
        operation.method = "GET";
        operation.path = "/tenants/:tenant_id/users";
        operation.pathParams = List.of(pathParam("tenant_id", "String"));
        operation.requestType = "GetUserRequest";
        operation.responseType = "UserResponse";

        var service = new ApiDefinition.Service();
        service.name = "UserService";
        service.description = "Users";
        service.operations = List.of(operation);
        api.services = List.of(service);

        api.types = List.of(
                bean("GetUserRequest", field("active", "Boolean", true), field("limit", "Integer", false)),
                bean("UserResponse", field("id", "String", true), field("profile", "Profile", false)),
                bean("Profile", field("name", "String", false)));
        return api;
    }

    @Test
    @SuppressWarnings("unchecked")
    void testListApiAppServicesIncludesInputAndOutputSchemas() {
        var loader = new InternalApiToolLoader(new FakeApiDefinitionService(buildApi()));

        var services = loader.listApiAppServices("test-app");

        assertEquals(1, services.size());
        var operation = services.getFirst().operations().getFirst();
        assertEquals("test_app_UserService_getUser", operation.toolName());
        assertEquals("GetUserRequest", operation.requestType());
        assertEquals("UserResponse", operation.responseType());

        var inputSchema = JsonUtil.toMap(operation.inputSchema());
        var inputProperties = (Map<String, Object>) inputSchema.get("properties");
        assertTrue(inputProperties.containsKey("tenant_id"));
        assertTrue(inputProperties.containsKey("active"));
        assertTrue(((List<String>) inputSchema.get("required")).contains("tenant_id"));
        assertTrue(((List<String>) inputSchema.get("required")).contains("active"));

        var outputSchema = JsonUtil.toMap(operation.outputSchema());
        var outputProperties = (Map<String, Object>) outputSchema.get("properties");
        assertTrue(outputProperties.containsKey("id"));
        var profile = (Map<String, Object>) outputProperties.get("profile");
        assertNotNull(((Map<String, Object>) profile.get("properties")).get("name"));
    }

    private static final class FakeApiDefinitionService extends ApiDefinitionService {
        private final ApiDefinition api;

        private FakeApiDefinitionService(ApiDefinition api) {
            this.api = api;
        }

        @Override
        public List<ApiDefinition> loadAll() {
            return List.of(api);
        }
    }
}
