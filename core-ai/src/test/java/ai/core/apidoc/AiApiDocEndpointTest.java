package ai.core.apidoc;

import ai.core.api.apidefinition.AiApiDefinition;
import ai.core.api.apidefinition.AiField;
import ai.core.api.apidefinition.AiOperation;
import ai.core.api.apidefinition.AiService;
import core.framework.api.json.Property;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.internal.module.ModuleContext;
import core.framework.json.JSON;
import core.framework.module.App;
import core.framework.module.SystemModule;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiApiDocEndpointTest {
    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void servesAnnotatedDefinitionOverHttp() throws Exception {
        var port = freePort();
        var app = new SampleApp(port);
        app.configure();
        try {
            app.context().startupHook.initialize();
            app.context().startupHook.start();

            var client = HTTPClient.builder().build();
            var response = client.execute(new HTTPRequest(HTTPMethod.GET, "http://127.0.0.1:" + port + "/_sys/api/ai"));

            assertEquals(200, response.statusCode);
            var definition = JSON.fromJSON(AiApiDefinition.class, response.text());
            assertEquals(AiApiDefinition.SCHEMA_V1, definition.schema);
            assertEquals("Echo app", definition.description);
            assertEquals("Echo service", definition.services.getFirst().description);
            var operation = definition.services.getFirst().operations.getFirst();
            assertEquals("Echo operation", operation.description);
            var requestType = definition.types.stream().filter(type -> type.name.endsWith("EchoRequest")).findFirst().orElseThrow();
            assertEquals("Message text", requestType.fields.getFirst().description);
        } finally {
            app.context().shutdownHook.run();
        }
    }

    @AiService(description = "Echo service")
    public interface EchoService {
        @POST
        @Path("/echo")
        @AiOperation(description = "Echo operation")
        EchoResponse echo(EchoRequest request);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public EchoResponse echo(EchoRequest request) {
            var response = new EchoResponse();
            response.message = request.message;
            return response;
        }
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

    static class SampleApp extends App {
        private final int port;

        SampleApp(int port) {
            this.port = port;
        }

        ModuleContext context() {
            return context;
        }

        @Override
        protected void initialize() {
            load(new SystemModule("sys.properties"));
            http().listenHTTP("127.0.0.1:" + port);
            api().service(EchoService.class, new EchoServiceImpl());
            load(new CoreAiApiDocModule().appDescription("Echo app"));
        }
    }
}
