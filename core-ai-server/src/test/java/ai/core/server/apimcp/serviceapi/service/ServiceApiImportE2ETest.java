package ai.core.server.apimcp.serviceapi.service;

import ai.core.api.apidefinition.AiField;
import ai.core.api.apidefinition.AiOperation;
import ai.core.api.apidefinition.AiParam;
import ai.core.api.apidefinition.AiService;
import ai.core.apidoc.CoreAiApiDocModule;
import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
import core.framework.api.json.Property;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.internal.module.ModuleContext;
import core.framework.module.App;
import core.framework.module.SystemModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceApiImportE2ETest {
    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void importsAnnotatedDefinitionOverHttp() throws Exception {
        var port = freePort();
        var app = new ImportApp(port);
        app.configure();
        try {
            app.context().startupHook.initialize();
            app.context().startupHook.start();

            var serviceApi = new ServiceApi();
            serviceApi.id = "1";
            serviceApi.name = "order";
            serviceApi.enabled = Boolean.TRUE;
            serviceApi.version = "1";
            serviceApi.baseUrl = "http://127.0.0.1:" + port;
            var store = mock(ServiceApiStore.class);
            when(store.get("1")).thenReturn(Optional.of(serviceApi));
            when(store.listAll()).thenReturn(List.of(serviceApi));
            var serviceApiService = new ServiceApiService();
            serviceApiService.store = store;

            serviceApiService.updateFromSysApi("1", "http://127.0.0.1:" + port + "/_sys/api/ai", "tester");

            assertEquals("core-ai", serviceApi.definitionFormat);
            assertNotNull(serviceApi.payload);
            verify(store).replace(serviceApi);

            var definitionService = new ApiDefinitionService();
            definitionService.apiService = serviceApiService;
            var api = definitionService.loadAll().getFirst();
            assertEquals("Order service", api.services.getFirst().description);
            var operation = api.services.getFirst().operations.getFirst();
            assertEquals("Create refund", operation.description);
            var requestType = api.types.stream().filter(type -> type.name.endsWith("CreateRefundRequest")).findFirst().orElseThrow();
            assertEquals("Refund amount", requestType.fields.getFirst().description);
        } finally {
            app.context().shutdownHook.run();
        }
    }

    @AiService(description = "Order service")
    public interface OrderService {
        @POST
        @Path("/orders/:orderId/refunds")
        @AiOperation(description = "Create refund")
        RefundResponse createRefund(@AiParam(description = "Order id") @PathParam("orderId") String orderId,
                                    CreateRefundRequest request);
    }

    public static class OrderServiceImpl implements OrderService {
        @Override
        public RefundResponse createRefund(String orderId, CreateRefundRequest request) {
            var response = new RefundResponse();
            response.refundId = "R1";
            return response;
        }
    }

    public static class CreateRefundRequest {
        @Property(name = "amount")
        @AiField(description = "Refund amount")
        public BigDecimal amount;
    }

    public static class RefundResponse {
        @Property(name = "refundId")
        public String refundId;
    }

    static class ImportApp extends App {
        private final int port;

        ImportApp(int port) {
            this.port = port;
        }

        ModuleContext context() {
            return context;
        }

        @Override
        protected void initialize() {
            load(new SystemModule("apidef-e2e.properties"));
            http().listenHTTP("127.0.0.1:" + port);
            api().service(OrderService.class, new OrderServiceImpl());
            load(new CoreAiApiDocModule().appDescription("Order app"));
        }
    }
}
