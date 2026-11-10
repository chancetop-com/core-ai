package ai.core.apidoc;

import ai.core.api.apidefinition.AiApiDefinition;
import ai.core.api.apidefinition.AiField;
import ai.core.api.apidefinition.AiOperation;
import ai.core.api.apidefinition.AiParam;
import ai.core.api.apidefinition.AiService;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiApiDocBuilderTest {
    private static AiApiDefinition.Type type(AiApiDefinition definition, String simpleName) {
        return definition.types.stream().filter(type -> simpleTypeName(type.name).equals(simpleName)).findFirst().orElseThrow();
    }

    private static AiApiDefinition.Field field(AiApiDefinition.Type type, String name) {
        return type.fields.stream().filter(field -> field.name.equals(name)).findFirst().orElseThrow();
    }

    private static String simpleTypeName(String name) {
        var index = name.lastIndexOf('$');
        return index < 0 ? name : name.substring(index + 1);
    }

    @Test
    void buildsAnnotatedDefinition() {
        var definition = new AiApiDocBuilder(Set.of(OrderService.class), Set.of(), "Order app").build();

        assertEquals(AiApiDefinition.SCHEMA_V1, definition.schema);
        assertEquals("Order app", definition.description);

        var service = definition.services.getFirst();
        assertEquals("OrderService", service.name);
        assertEquals("Order service", service.description);

        var refund = service.operations.stream().filter(operation -> operation.name.equals("createRefund")).findFirst().orElseThrow();
        assertEquals("Create refund", refund.description);
        assertEquals("{\"orderId\":\"O123\"}", refund.example);
        assertEquals("POST", refund.method);
        assertEquals("/orders/:orderId/refunds", refund.path);
        assertEquals("CreateRefundRequest", simpleTypeName(refund.requestType));

        var orderId = refund.pathParams.getFirst();
        assertEquals("orderId", orderId.name);
        assertEquals("Order id", orderId.description);
        assertEquals("O123", orderId.example);

        var request = type(definition, "CreateRefundRequest");
        var amount = field(request, "amount");
        assertEquals("Refund amount", amount.description);
        assertEquals("12.50", amount.example);
        assertEquals(Boolean.TRUE, amount.constraints.notNull);

        var items = field(request, "items");
        assertNull(items.description);

        var item = type(definition, "RefundItem");
        assertEquals("SKU", field(item, "sku").description);

        var response = type(definition, "RefundResponse");
        assertEquals("Refund id", field(response, "refundId").description);

        var orders = service.operations.stream().filter(operation -> operation.name.equals("orders")).findFirst().orElseThrow();
        assertNull(orders.description);
        assertNull(orders.example);
        assertEquals("GET", orders.method);
    }

    @Test
    void supportsGenericAndEnumFields() {
        var definition = new AiApiDocBuilder(Set.of(InventoryService.class), Set.of(), null).build();
        var response = type(definition, "InventoryResponse");
        assertEquals("items", field(response, "items").name);
        var item = type(definition, "InventoryItem");
        assertEquals("SKU", field(item, "sku").description);
        var status = type(definition, "InventoryStatus");
        assertTrue(status.enumConstants.stream().anyMatch(constant -> constant.name.equals("AVAILABLE")));
        assertNull(definition.description);
    }

    public enum InventoryStatus {
        @Property(name = "AVAILABLE")
        AVAILABLE
    }

    @AiService(description = "Order service")
    public interface OrderService {
        @POST
        @Path("/orders/:orderId/refunds")
        @AiOperation(description = "Create refund", example = "{\"orderId\":\"O123\"}")
        RefundResponse createRefund(@AiParam(description = "Order id", example = "O123") @PathParam("orderId") String orderId,
                                    CreateRefundRequest request);

        @GET
        @Path("/orders")
        List<Order> orders();
    }

    public interface InventoryService {
        @GET
        @Path("/inventory")
        InventoryResponse inventory();
    }

    public static class CreateRefundRequest {
        @NotNull
        @Property(name = "amount")
        @AiField(description = "Refund amount", example = "12.50")
        public BigDecimal amount;

        @Property(name = "reason")
        @AiField(description = "Reason", example = "damaged")
        public String reason;

        @Property(name = "items")
        public List<RefundItem> items;
    }

    public static class RefundItem {
        @Property(name = "sku")
        @AiField(description = "SKU")
        public String sku;
    }

    public static class RefundResponse {
        @Property(name = "refundId")
        @AiField(description = "Refund id")
        public String refundId;
    }

    public static class Order {
        @Property(name = "id")
        public String id;
    }

    public static class InventoryResponse {
        @Property(name = "items")
        public List<InventoryItem> items;

        @Property(name = "status")
        public InventoryStatus status;
    }

    public static class InventoryItem {
        @Property(name = "sku")
        @AiField(description = "SKU")
        public String sku;
    }
}
