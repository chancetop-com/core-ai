package ai.core.server.apitoolhub;

import ai.core.server.tool.InternalApiToolLoader;
import ai.core.server.tool.ToolRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiToolCatalogServiceTest {
    private ApiToolCatalogService catalog;

    @BeforeEach
    void setUp() {
        var registry = mock(ToolRegistryService.class);
        var app = new InternalApiToolLoader.ApiAppCatalog("order-service", "http://order:8080", "v1", "Order backend",
                List.of(new InternalApiToolLoader.ApiServiceCatalog("refund", "Refund operations", List.of(
                        op("order-service", "refund", "create", "Create a refund for an order", "POST", "/orders/:orderId/refunds"),
                        op("order-service", "refund", "get", "Get one refund", "GET", "/orders/:orderId/refunds/:refundId"))),
                        new InternalApiToolLoader.ApiServiceCatalog("order", "Order operations", List.of(
                                op("order-service", "order", "get", "Get an order", "GET", "/orders/:orderId")))));
        var payment = new InternalApiToolLoader.ApiAppCatalog("payment-api", "http://payment:8080", "v1", "Payments",
                List.of(new InternalApiToolLoader.ApiServiceCatalog("refund", "Payment refunds", List.of(
                        op("payment-api", "refund", "status", "Refund status", "GET", "/refunds/:id/status")))));
        when(registry.loadApiCatalog()).thenReturn(List.of(app, payment));
        catalog = new ApiToolCatalogService();
        catalog.toolRegistryService = registry;
        catalog.refresh();
    }

    // tool names follow the loader rule (app_service_operation) so the snapshot lookup table matches production
    private InternalApiToolLoader.ApiOperationInfo op(String app, String service, String name, String description, String method, String path) {
        return new InternalApiToolLoader.ApiOperationInfo(name, app.replaceAll("-", "_") + "_" + service + "_" + name,
                description, method, path, null, null,
                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}", null, null, null, null);
    }

    @Test
    void appsCarryCountsAndSortByAppName() {
        var apps = catalog.apps();
        assertEquals(2, apps.size());
        assertEquals("order-service", apps.get(0).app());
        assertEquals(2, apps.get(0).serviceCount());
        assertEquals(3, apps.get(0).operationCount());
        assertEquals("payment-api", apps.get(1).app());
    }

    @Test
    void findResolvesByQualifiedNameOnlyWhenEnabled() {
        assertNotNull(catalog.find("order-service", "refund", "create"));
        assertNull(catalog.find("order-service", "refund", "delete"));
        assertNull(catalog.find("order-service", "missing", "create"));
    }

    @Test
    void findByToolNameMatchesFunctionName() {
        var entry = catalog.findByToolName("order_service_refund_create");
        assertNotNull(entry);
        assertEquals("order-service", entry.app());
        assertEquals("api-operation:order-service:refund:create", entry.refId());
        assertEquals("order-service/refund/create", entry.qualifiedName());
        assertNull(catalog.findByToolName("order_service_refund_delete"));
    }

    @Test
    void searchKeepsOnlyOperationsWhereEveryTokenHits() {
        var outcome = catalog.search("refund get", null, null, null);
        // "refund get": hits order-service refund/get (name+desc) and payment-api refund/status (desc);
        // order-service refund/create misses "get"; order-service order/get misses "refund"
        assertEquals(2, outcome.operations().size());
        assertEquals("order-service/refund/get", outcome.operations().get(0).operation().qualifiedName());
        assertEquals("payment-api/refund/status", outcome.operations().get(1).operation().qualifiedName());
    }

    @Test
    void searchMethodAndPathTokensKeepAndScoreOperations() {
        var outcome = catalog.search("post refund", null, null, null);
        var qualified = outcome.operations().stream()
                .map(scored -> scored.operation().qualifiedName())
                .toList();
        // "post" hits only via the method layer of order-service/refund/create
        assertEquals(List.of("order-service/refund/create"), qualified);
        int score = outcome.operations().getFirst().score();
        // "refund": tool-name layer (10) + description layer (2) + path layer (3, /orders/:orderId/refunds);
        // "post": method bonus (5)
        assertEquals(ai.core.tool.ToolSearchScorer.TOOL_NAME_CONTAINS_TOKEN
                + ai.core.tool.ToolSearchScorer.DESCRIPTION_CONTAINS_TOKEN + 3 + 5, score);
    }

    @Test
    void searchAppFilterLiftsPerAppCap() {
        var outcome = catalog.search("refund", "order-service", null, 20);
        assertEquals(2, outcome.operations().size());   // both refund ops of the app (no 3-cap needed)
        assertEquals(1, outcome.apps().size());
    }

    @Test
    void searchBrandHitRanksAppFirstAndDiversifiesAcrossApps() {
        var outcome = catalog.search("order", null, null, null);
        // brand hit on app "order-service": every order op matches token "order"
        assertEquals(1, outcome.apps().size());
        assertEquals(50, outcome.apps().get(0).score());   // server name contains token
        var qualified = outcome.operations().stream()
                .map(scored -> scored.operation().qualifiedName())
                .toList();
        // diversified: at most 3 per app, so all 3 order ops listed (only one app has hits)
        assertEquals(3, qualified.size());
    }

    @Test
    void queryLessSearchListsFlatBoundedByLimit() {
        var outcome = catalog.search(null, null, null, 2);
        assertEquals(List.of(), outcome.apps());
        assertEquals(2, outcome.operations().size());
    }
}
