package ai.core.server.apimcp.serviceapi.service;

import ai.core.api.apidefinition.AiField;
import ai.core.api.apidefinition.AiOperation;
import ai.core.api.apidefinition.AiService;
import ai.core.apidoc.AiApiDocBuilder;
import ai.core.server.apimcp.serviceapi.domain.FieldAdditional;
import ai.core.server.apimcp.serviceapi.domain.OperationAdditional;
import ai.core.server.apimcp.serviceapi.domain.PathParamAdditional;
import ai.core.server.apimcp.serviceapi.domain.ServiceAdditional;
import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
import ai.core.server.apimcp.serviceapi.domain.TypeAdditional;
import core.framework.api.json.Property;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.json.JSON;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiDefinitionServiceTest {
    private static final String LEGACY_PAYLOAD = """
            {
              "app": "test-app",
              "version": "v1",
              "services": [{
                "name": "UserService",
                "operations": [{
                  "name": "getUser",
                  "method": "GET",
                  "path": "/users/:id",
                  "pathParams": [{"name": "id", "type": "String"}],
                  "requestType": "GetUserRequest",
                  "optional": false,
                  "deprecated": false
                }]
              }],
              "types": [
                {"name": "GetUserRequest", "type": "bean", "fields": [
                  {"name": "name", "type": "String", "constraints": {"notNull": true}}
                ]},
                {"name": "UserResponse", "type": "bean", "fields": [
                  {"name": "id", "type": "String", "constraints": {}}
                ]}
              ]
            }
            """;

    private static final String ANNOTATED_PAYLOAD = """
            {
              "schema": "core-ai/api-definition/1",
              "description": "App desc",
              "app": "test-app",
              "version": "v1",
              "services": [{
                "name": "UserService",
                "description": "User service",
                "operations": [{
                  "name": "getUser",
                  "description": "Get user",
                  "example": "{\\"id\\":\\"U1\\"}",
                  "method": "GET",
                  "path": "/users/:id",
                  "pathParams": [{"name": "id", "description": "User id", "example": "U1", "type": "String"}],
                  "requestType": "GetUserRequest",
                  "responseType": "UserResponse",
                  "optional": false,
                  "deprecated": false
                }]
              }],
              "types": [
                {"name": "GetUserRequest", "type": "bean", "fields": [
                  {"name": "name", "description": "User name", "example": "Stephen", "type": "String", "constraints": {"notNull": true}}
                ]},
                {"name": "UserResponse", "type": "bean", "fields": [
                  {"name": "id", "description": "User id", "type": "String", "constraints": {}}
                ]}
              ]
            }
            """;

    private static ServiceApi serviceApi(String payload) {
        var api = new ServiceApi();
        api.id = "1";
        api.name = "test";
        api.enabled = Boolean.TRUE;
        api.baseUrl = "http://localhost";
        api.version = "v1";
        api.payload = payload;
        return api;
    }

    private static ApiDefinitionService service(ServiceApi... apis) {
        var serviceApiService = mock(ServiceApiService.class);
        when(serviceApiService.findAll()).thenReturn(List.of(apis));
        if (apis.length == 1) {
            when(serviceApiService.getByName(apis[0].name)).thenReturn(apis[0]);
        }
        var service = new ApiDefinitionService();
        service.apiService = serviceApiService;
        return service;
    }

    @Test
    void loadsLegacyPayload() {
        var api = service(serviceApi(LEGACY_PAYLOAD)).load("test");

        assertEquals("test-app", api.app);
        assertNull(api.description);
        var operation = api.services.getFirst().operations.getFirst();
        assertEquals("getUser", operation.description);
        assertNull(operation.example);
        assertNull(operation.pathParams.getFirst().description);
        var field = api.types.stream().filter(type -> "GetUserRequest".equals(type.name)).findFirst().orElseThrow().fields.getFirst();
        assertNull(field.description);
        assertNull(field.example);
        assertEquals(Boolean.TRUE, field.constraints.notNull);
    }

    @Test
    void loadsAnnotatedPayload() {
        var api = service(serviceApi(ANNOTATED_PAYLOAD)).load("test");

        assertEquals("App desc", api.description);
        assertEquals("User service", api.services.getFirst().description);
        var operation = api.services.getFirst().operations.getFirst();
        assertEquals("Get user", operation.description);
        assertEquals("{\"id\":\"U1\"}", operation.example);
        assertEquals("User id", operation.pathParams.getFirst().description);
        assertEquals("U1", operation.pathParams.getFirst().example);
        var field = api.types.stream().filter(type -> "GetUserRequest".equals(type.name)).findFirst().orElseThrow().fields.getFirst();
        assertEquals("User name", field.description);
        assertEquals("Stephen", field.example);
    }

    @Test
    void manualAdditionalOverridesPayload() {
        var serviceApi = serviceApi(ANNOTATED_PAYLOAD);
        var serviceAdditional = new ServiceAdditional();
        serviceAdditional.name = "UserService";
        serviceAdditional.description = "Manual service";
        serviceAdditional.enabled = Boolean.TRUE;
        var operationAdditional = new OperationAdditional();
        operationAdditional.name = "getUser";
        operationAdditional.description = "Manual operation";
        operationAdditional.example = "manual example";
        operationAdditional.enabled = Boolean.TRUE;
        operationAdditional.needAuth = Boolean.TRUE;
        var pathParamAdditional = new PathParamAdditional();
        pathParamAdditional.name = "id";
        pathParamAdditional.description = "Manual id";
        pathParamAdditional.example = "M1";
        operationAdditional.pathParamAdditional = List.of(pathParamAdditional);
        serviceAdditional.operationAdditional = List.of(operationAdditional);
        serviceApi.serviceAdditional = List.of(serviceAdditional);
        var typeAdditional = new TypeAdditional();
        typeAdditional.name = "GetUserRequest";
        var fieldAdditional = new FieldAdditional();
        fieldAdditional.name = "name";
        fieldAdditional.description = "Manual name";
        fieldAdditional.example = "Manual example";
        typeAdditional.fieldAdditional = List.of(fieldAdditional);
        serviceApi.typeAdditional = List.of(typeAdditional);

        var api = service(serviceApi).load("test");

        assertEquals("Manual service", api.services.getFirst().description);
        var operation = api.services.getFirst().operations.getFirst();
        assertEquals("Manual operation", operation.description);
        assertEquals("manual example", operation.example);
        assertEquals(Boolean.TRUE, operation.needAuth);
        assertEquals("Manual id", operation.pathParams.getFirst().description);
        assertEquals("M1", operation.pathParams.getFirst().example);
        var field = api.types.stream().filter(type -> "GetUserRequest".equals(type.name)).findFirst().orElseThrow().fields.getFirst();
        assertEquals("Manual name", field.description);
        assertEquals("Manual example", field.example);
    }

    @Test
    void skipsBrokenPayload() {
        var broken = serviceApi("not-json");
        broken.name = "broken";
        var good = serviceApi(ANNOTATED_PAYLOAD);
        good.name = "good";

        var apis = service(broken, good).loadAll();

        assertEquals(1, apis.size());
        assertEquals("test-app", apis.getFirst().app);
    }

    @Test
    void roundTripsCoreAiDefinition() {
        var definition = new AiApiDocBuilder(Set.of(EchoService.class), Set.of(), "Round trip app").build();
        var payload = JSON.toJSON(definition);
        var service = new ServiceApiService();

        assertEquals("core-ai", service.definitionFormat(payload));

        var serviceApi = serviceApi(payload);
        serviceApi.name = "round-trip";
        var api = service(serviceApi).load("round-trip");

        assertEquals("Round trip app", api.description);
        var operation = api.services.getFirst().operations.getFirst();
        assertEquals("Echo operation", operation.description);
        var field = api.types.stream().filter(type -> type.name.endsWith("EchoRequest")).findFirst().orElseThrow().fields.getFirst();
        assertEquals("Message text", field.description);
    }

    @Test
    void detectsDefinitionFormat() {
        var service = new ServiceApiService();

        assertEquals("core-ng", service.definitionFormat(LEGACY_PAYLOAD));
        assertEquals("core-ai", service.definitionFormat(ANNOTATED_PAYLOAD));
        assertThrows(BadRequestException.class, () -> service.definitionFormat("not-json"));
        assertThrows(BadRequestException.class, () -> service.definitionFormat("{\"app\":\"test\"}"));
        assertThrows(BadRequestException.class, () -> service.definitionFormat("""
                {"schema":"core-ai/api-definition/2","app":"test","services":[{"name":"S"}]}
                """));
    }

    @AiService(description = "Echo service")
    public interface EchoService {
        @POST
        @Path("/echo")
        @AiOperation(description = "Echo operation")
        EchoResponse echo(EchoRequest request);
    }

    public static class EchoRequest {
        @Property(name = "message")
        @AiField(description = "Message text")
        public String message;
    }

    public static class EchoResponse {
        @Property(name = "message")
        @AiField(description = "Returned message")
        public String message;
    }
}
