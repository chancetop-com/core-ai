package ai.core.server.apitoolhub;

import ai.core.api.server.apitoolhub.ApiToolHubOperationDetail;
import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.server.apitoolhub.ApiToolCatalogService.CatalogOperation;
import ai.core.server.domain.User;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.tool.ToolRegistryService;
import core.framework.http.HTTPResponse;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiToolHubServiceTest {
    private ApiToolCatalogService catalog;
    private ApiToolHubAccessPolicy accessPolicy;
    private HubCallAuditService auditService;
    private ToolRegistryService registry;
    private ApiToolHubService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        catalog = mock(ApiToolCatalogService.class);
        accessPolicy = mock(ApiToolHubAccessPolicy.class);
        auditService = mock(HubCallAuditService.class);
        registry = mock(ToolRegistryService.class);
        var userCollection = (MongoCollection<User>) mock(MongoCollection.class);
        service = new ApiToolHubService();
        service.catalog = catalog;
        service.accessPolicy = accessPolicy;
        service.auditService = auditService;
        service.toolRegistryService = registry;
        service.userCollection = userCollection;
        when(userCollection.get("user-1")).thenReturn(Optional.of(user()));
        when(auditService.begin(any(HubCallAuditService.BeginRequest.class))).thenReturn("audit-1");
    }

    @Test
    void describeReturnsDetailWithSchemas() {
        when(catalog.find("order-service", "refund", "create")).thenReturn(operation());

        ApiToolHubOperationDetail detail = service.describe("order-service", "refund", "create");

        assertEquals("order-service/refund/create", detail.qualifiedName);
        assertEquals("order_service_refund_create", detail.toolName);
        assertEquals("api-operation:order-service:refund:create", detail.refId);
        assertEquals("POST", detail.method);
        assertEquals("/orders/:orderId/refunds", detail.path);
        assertEquals("{\"type\":\"object\"}", detail.inputSchema);
        assertEquals("{\"refundId\":\"string\"}", detail.outputSchema);
        assertEquals("example-json", detail.example);
        assertTrue(detail.needAuth);
    }

    @Test
    void describeUnknownOperationIsNotFound() {
        when(catalog.find("order-service", "refund", "create")).thenReturn(null);
        assertThrows(NotFoundException.class, () -> service.describe("order-service", "refund", "create"));
    }

    @Test
    void callMapsSuccessfulBackendToSuccessResponse() {
        when(catalog.find("order-service", "refund", "create")).thenReturn(operation());
        when(registry.callServiceApiOperation(eq("order-service"), eq("refund"), eq("create"), any(String.class)))
                .thenReturn(response(200, "{\"refundId\":\"R9\"}"));

        HubCallResponse response = service.call("user-1", "cli", "order-service", "refund", "create",
                request("{\"orderId\":\"O1\"}", null));

        assertTrue(response.success);
        assertFalse(response.isError);
        assertEquals(200, response.statusCode);
        assertEquals("{\"refundId\":\"R9\"}", response.text);
        assertNotNull(response.callId);
        verify(accessPolicy).checkCanCall("user-1", "order-service");
        var captor = ArgumentCaptor.forClass(HubCallAuditService.BeginRequest.class);
        verify(auditService).begin(captor.capture());
        var beginRequest = captor.getValue();
        assertEquals(HubCallAuditService.KIND_API_TOOL, beginRequest.kind());
        assertEquals("user-1", beginRequest.userId());
        assertEquals("cli", beginRequest.source());
        assertEquals("order-service/refund/create", beginRequest.target());
        assertEquals("api-operation:order-service:refund:create", beginRequest.refId());
        assertEquals("order-service", beginRequest.group());
        assertEquals("create", beginRequest.name());
    }

    @Test
    void callMapsBackendRejectionToBusinessErrorWithStatus() {
        when(catalog.find("order-service", "refund", "create")).thenReturn(operation());
        when(registry.callServiceApiOperation(eq("order-service"), eq("refund"), eq("create"), any(String.class)))
                .thenReturn(response(422, "{\"error\":\"bad amount\"}"));

        HubCallResponse response = service.call("user-1", "cli", "order-service", "refund", "create",
                request("{}", null));

        assertFalse(response.success);
        assertTrue(response.isError);
        assertEquals(422, response.statusCode);
        assertEquals("{\"error\":\"bad amount\"}", response.text);
    }

    @Test
    void callMapsTransportFailureToBusinessErrorWithoutStatus() {
        when(catalog.find("order-service", "refund", "create")).thenReturn(operation());
        when(registry.callServiceApiOperation(eq("order-service"), eq("refund"), eq("create"), any(String.class)))
                .thenThrow(new IllegalStateException("Call api[http://order:8080/orders/O1/refunds, {...}] failed: connection refused"));

        HubCallResponse response = service.call("user-1", "cli", "order-service", "refund", "create",
                request("{}", null));

        assertFalse(response.success);
        assertTrue(response.isError);
        assertNull(response.statusCode);
        assertTrue(response.text.contains("connection refused"));
        assertNotNull(response.callId);
    }

    @Test
    void callRejectsMalformedArguments() {
        when(catalog.find("order-service", "refund", "create")).thenReturn(operation());
        assertThrows(BadRequestException.class,
                () -> service.call("user-1", "cli", "order-service", "refund", "create", request("{not json", null)));
    }

    @Test
    void callRejectsOutOfRangeTimeout() {
        when(catalog.find("order-service", "refund", "create")).thenReturn(operation());
        assertThrows(BadRequestException.class,
                () -> service.call("user-1", "cli", "order-service", "refund", "create", request("{}", 999)));
    }

    @Test
    void lookupResolvesBareFunctionName() {
        when(catalog.findByToolName("order_service_refund_create")).thenReturn(operation());
        var response = service.lookup("order_service_refund_create");
        assertEquals("order-service", response.operation.app);
        assertEquals("refund/create", response.operation.service + "/" + response.operation.name);
        when(catalog.findByToolName("order_service_refund_delete")).thenReturn(null);
        assertThrows(NotFoundException.class, () -> service.lookup("order_service_refund_delete"));
    }

    private CatalogOperation operation() {
        return new CatalogOperation("order-service", "refund", "create", "order_service_refund_create",
                "api-operation:order-service:refund:create", "Create a refund for an order", "POST",
                "/orders/:orderId/refunds", Boolean.TRUE, Boolean.FALSE, "example-json", "CreateRefundRequest",
                "CreateRefundResponse", "{\"type\":\"object\"}", "{\"refundId\":\"string\"}");
    }

    private HubCallRequest request(String arguments, Integer timeoutSeconds) {
        var request = new HubCallRequest();
        request.arguments = arguments;
        request.timeoutSeconds = timeoutSeconds;
        return request;
    }

    private HTTPResponse response(int statusCode, String body) {
        var headers = new HashMap<String, String>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        return new HTTPResponse(statusCode, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private User user() {
        var user = new User();
        user.id = "user-1";
        user.externalId = "ext-1";
        return user;
    }
}
