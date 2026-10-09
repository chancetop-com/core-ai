package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol-layer behavior of the transport: initialize gate, dispatch, error mapping, heartbeat,
 * shutdown and EOF handling, driven through piped stdio with a fake engine.
 *
 * @author stephen
 */
class AppServerTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppServerTest.class);

    private Harness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void initializeMustBeTheFirstRequest() throws Exception {
        harness = new Harness(60_000L);
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":{}}");
        var frame = harness.next();
        assertEquals(RpcException.INVALID_REQUEST, frame.path("error").path("code").asInt());
        assertTrue(frame.path("error").path("message").asText().contains("initialize"));
    }

    @Test
    void initializeReturnsResultThenReadyNotification() throws Exception {
        harness = new Harness(60_000L);
        harness.initialize();
        var response = harness.next();
        assertEquals("1.0", response.path("result").path("protocolVersion").asText());
        var ready = harness.next();
        assertEquals("engine/ready", ready.path("method").asText());
    }

    @Test
    void unknownMethodIsRejected() throws Exception {
        harness = new Harness(60_000L);
        harness.initAndDrain();
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"no/such/method\",\"params\":{}}");
        var frame = harness.next();
        assertEquals(RpcException.METHOD_NOT_FOUND, frame.path("error").path("code").asInt());
    }

    @Test
    void engineCallResultIsRelayed() throws Exception {
        harness = new Harness(60_000L);
        harness.initAndDrain();
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"echo\",\"params\":{\"value\":\"hi\"}}");
        var frame = harness.next();
        assertEquals("echo", frame.path("result").path("method").asText());
        assertEquals("hi", frame.path("result").path("value").asText());
    }

    @Test
    void engineBusinessErrorIsMappedToErrorFrame() throws Exception {
        harness = new Harness(60_000L);
        harness.initAndDrain();
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"echo\",\"params\":{\"mode\":\"boom\"}}");
        var frame = harness.next();
        assertEquals(RpcException.INVALID_PARAMS, frame.path("error").path("code").asInt());
        assertEquals("bad param", frame.path("error").path("message").asText());
    }

    @Test
    void parseErrorIsReportedWithNullId() throws Exception {
        harness = new Harness(60_000L);
        harness.send("this is not json");
        var frame = harness.next();
        assertEquals(RpcException.PARSE_ERROR, frame.path("error").path("code").asInt());
        assertTrue(frame.path("id").isNull());
    }

    @Test
    void oversizedFrameIsRejected() throws Exception {
        harness = new Harness(60_000L);
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\",\"params\":{\"v\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}}");
        var frame = harness.next();
        assertEquals(RpcException.INVALID_REQUEST, frame.path("error").path("code").asInt());
    }

    @Test
    void notificationsGetNoResponse() throws Exception {
        harness = new Harness(60_000L);
        harness.initAndDrain();
        harness.send("{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{\"value\":\"silent\"}}");
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"echo\",\"params\":{\"value\":\"loud\"}}");
        var frame = harness.next();
        assertEquals(5, frame.path("id").asInt());
        assertEquals("loud", frame.path("result").path("value").asText());
    }

    @Test
    void engineNotificationsReachTheClient() throws Exception {
        harness = new Harness(60_000L);
        harness.initAndDrain();
        harness.engine.emit("session/event", JsonUtil.OBJECT_MAPPER.createObjectNode().put("sessionId", "s1"));
        var frame = harness.next();
        assertEquals("session/event", frame.path("method").asText());
        assertEquals("s1", frame.path("params").path("sessionId").asText());
    }

    @Test
    void heartbeatIsEmittedAfterInitialize() throws Exception {
        harness = new Harness(30L);
        harness.initialize();
        harness.next();
        var ready = harness.next();
        assertEquals("engine/ready", ready.path("method").asText());
        JsonNode heartbeat = null;
        for (int i = 0; i < 50 && heartbeat == null; i++) {
            var frame = harness.next();
            if ("engine/heartbeat".equals(frame.path("method").asText())) {
                heartbeat = frame;
            }
        }
        assertNotNull(heartbeat, "heartbeat notification expected");
    }

    @Test
    void shutdownRespondsThenStopsTheServer() throws Exception {
        harness = new Harness(60_000L);
        harness.initAndDrain();
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"shutdown\",\"params\":{}}");
        var frame = harness.next();
        assertEquals(6, frame.path("id").asInt());
        assertTrue(harness.awaitServerExit());
        assertTrue(harness.engine.shutdownCalled);
    }

    @Test
    void stdinEofStopsTheServer() throws Exception {
        harness = new Harness(60_000L);
        harness.initAndDrain();
        harness.closeClientInput();
        assertTrue(harness.awaitServerExit());
        assertTrue(harness.engine.shutdownCalled);
    }

    private static final class FakeEngine implements EngineApi {
        private NotificationSink sink = (method, params) -> { };
        private volatile boolean shutdownCalled;

        @Override
        public void attach(NotificationSink sink) {
            this.sink = sink;
        }

        @Override
        public Set<String> supportedMethods() {
            return Set.of("echo");
        }

        @Override
        public JsonNode initialize(ObjectNode params) {
            var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
            node.put("protocolVersion", "1.0");
            return node;
        }

        @Override
        public JsonNode call(String method, ObjectNode params) {
            if ("boom".equals(params.path("mode").asText())) {
                throw RpcException.invalidParams("bad param");
            }
            var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
            node.put("method", method);
            node.put("value", params.path("value").asText(""));
            return node;
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }

        void emit(String method, JsonNode params) {
            sink.notify(method, params);
        }
    }

    private static final class Harness {
        private final FakeEngine engine = new FakeEngine();
        private final PipedOutputStream clientWrite = new PipedOutputStream();
        private final BufferedWriter clientWriter;
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final Thread serverThread;

        Harness(long heartbeatMillis) throws IOException {
            var serverIn = new PipedInputStream(clientWrite, 64 * 1024);
            var clientRead = new PipedInputStream();
            var serverOut = new PipedOutputStream(clientRead);
            clientWriter = new BufferedWriter(new OutputStreamWriter(clientWrite, StandardCharsets.UTF_8));
            startReader(clientRead);
            var server = new AppServer(serverIn, serverOut, engine);
            server.setHeartbeatMillis(heartbeatMillis);
            serverThread = new Thread(server::run, "app-server-test");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        void send(String json) throws IOException {
            clientWriter.write(json);
            clientWriter.write('\n');
            clientWriter.flush();
        }

        void initialize() throws IOException {
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1.0\"}}");
        }

        /** initialize + consume its response and the engine/ready notification. */
        void initAndDrain() throws Exception {
            initialize();
            next();
            next();
        }

        ObjectNode next() throws Exception {
            var line = frames.poll(10, TimeUnit.SECONDS);
            assertNotNull(line, "expected a frame");
            return (ObjectNode) JsonUtil.OBJECT_MAPPER.readTree(line);
        }

        void closeClientInput() throws IOException {
            clientWrite.close();
        }

        boolean awaitServerExit() throws InterruptedException {
            serverThread.join(10_000);
            return !serverThread.isAlive();
        }

        void close() {
            try {
                clientWriter.close();
            } catch (IOException e) {
                frames.offer("{\"closed\":true}");
            }
        }

        private void startReader(InputStream in) {
            var readerThread = new Thread(() -> drain(in), "app-server-test-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void drain(InputStream in) {
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                readLines(reader);
            } catch (IOException e) {
                LOGGER.debug("app-server test reader closed: {}", e.getMessage());
            }
        }

        private void readLines(BufferedReader reader) {
            while (true) {
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    return;
                }
                if (line == null) {
                    return;
                }
                frames.add(line);
            }
        }
    }
}
