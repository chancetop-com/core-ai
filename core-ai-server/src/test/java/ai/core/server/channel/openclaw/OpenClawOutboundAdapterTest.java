package ai.core.server.channel.openclaw;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.channel.ChannelMessage;
import ai.core.server.sandbox.SandboxService;
import ai.core.utils.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proactive send through the OpenClaw Channel Gateway: target routing, payload shape,
 * HMAC signing and the "never break the caller" failure behaviour.
 *
 * <p>Runs a real HTTP server on loopback standing in for the sandbox runtime's
 * {@code /ocg/send} proxy.
 *
 * @author stephen
 */
class OpenClawOutboundAdapterTest {
    private static final String CHANNEL_ID = "ch-openclaw";
    private static final String OCG_CONFIG_ID = "ocg-1";
    private static final String SECRET = "shared-secret";

    private HttpServer server;
    private int sandboxPort;
    private final List<Captured> captured = new ArrayList<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody =
            "{\"ok\":true,\"channel\":\"qqbot\",\"to\":\"qqbot:c2c:***\",\"chunks\":1,\"messageId\":\"m1\"}";

    @BeforeEach
    void startGatewayStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocg/send", exchange -> {
            var body = exchange.getRequestBody().readAllBytes();
            captured.add(new Captured(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-OCG-Signature"), body));
            var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        sandboxPort = server.getAddress().getPort();
    }

    @AfterEach
    void stopGatewayStub() {
        if (server != null) server.stop(0);
    }

    @Test
    void routesTargetToGatewayChannelAndSignsBody() {
        var adapter = adapter(channelsJson("qqbot", "telegram"), true, SECRET);

        adapter.sendMessage(ChannelMessage.text("daily report is ready"), CHANNEL_ID,
                "qqbot:c2c:OPENID", "qqbot:c2c:OPENID", null, Map.of());

        assertEquals(1, captured.size());
        var request = captured.get(0);
        assertEquals("/ocg/send", request.path());

        var payload = asJson(request.text());
        assertEquals("qqbot", payload.get("channel"));
        assertEquals("qqbot:c2c:OPENID", payload.get("to"));
        assertEquals("daily report is ready", payload.get("text"));
        assertNull(payload.get("mediaUrl"));
        assertEquals("sha256=" + hmac(request.body(), SECRET), request.signature());
    }

    @Test
    void resolvesSoleConfiguredChannelForUnprefixedTarget() {
        var adapter = adapter(channelsJson("telegram"), true, SECRET);

        adapter.sendMessage(ChannelMessage.text("cost alert"), CHANNEL_ID,
                "123456789", "123456789", null, Map.of());

        assertEquals(1, captured.size());
        assertEquals("telegram", asJson(captured.get(0).text()).get("channel"));
    }

    @Test
    void skipsAmbiguousUnprefixedTarget() {
        var adapter = adapter(channelsJson("qqbot", "telegram"), true, SECRET);

        adapter.sendMessage(ChannelMessage.text("hi"), CHANNEL_ID, "123456789", "123456789", null, Map.of());

        assertTrue(captured.isEmpty(), "ambiguous target must not be delivered");
    }

    @Test
    void sendsMediaAndThreadReference() {
        var adapter = adapter(channelsJson("qqbot"), true, SECRET);

        adapter.sendMessage(ChannelMessage.media("", "https://example.com/x.png", "image"), CHANNEL_ID,
                "qqbot:group:123", "qqbot:group:123", "msg-7", Map.of());

        assertEquals(1, captured.size());
        var payload = asJson(captured.get(0).text());
        assertEquals("https://example.com/x.png", payload.get("mediaUrl"));
        assertEquals("msg-7", payload.get("replyToId"));
        assertFalse(payload.containsKey("text"), "blank caption must not be sent as text");
    }

    @Test
    void omitsSignatureWhenSecretMissing() {
        var adapter = adapter(channelsJson("qqbot"), true, null);

        adapter.sendMessage(ChannelMessage.text("hi"), CHANNEL_ID, "qqbot:c2c:OPENID", "qqbot:c2c:OPENID", null, Map.of());

        assertEquals(1, captured.size());
        assertNull(captured.get(0).signature());
    }

    @Test
    void skipsWhenChannelHasNoGatewayConfig() {
        var configStore = mock(OcgConfigStore.class);
        var adapter = new OpenClawOutboundAdapter();
        adapter.ocgConfigStore = configStore;
        adapter.sandboxService = mock(SandboxService.class);
        adapter.ocgSandboxService = mock(OcgSandboxService.class);

        adapter.sendMessage(ChannelMessage.text("hi"), CHANNEL_ID, "qqbot:c2c:OPENID", "qqbot:c2c:OPENID", null, Map.of());

        assertTrue(captured.isEmpty());
    }

    @Test
    void skipsWhenGatewayConfigDisabled() {
        var adapter = adapter(channelsJson("qqbot"), false, SECRET);

        adapter.sendMessage(ChannelMessage.text("hi"), CHANNEL_ID, "qqbot:c2c:OPENID", "qqbot:c2c:OPENID", null, Map.of());

        assertTrue(captured.isEmpty());
    }

    @Test
    void skipsWhenSandboxIsNotRunning() {
        var adapter = adapter(channelsJson("qqbot"), false, SECRET, false);

        adapter.sendMessage(ChannelMessage.text("hi"), CHANNEL_ID, "qqbot:c2c:OPENID", "qqbot:c2c:OPENID", null, Map.of());

        assertTrue(captured.isEmpty());
    }

    @Test
    void attachesSandboxOnDemandWhenReplicaHasNotSeenIt() {
        var config = new OcgConfigView();
        config.id = OCG_CONFIG_ID;
        config.channelId = CHANNEL_ID;
        config.configJson = channelsJson("qqbot");
        config.enabled = Boolean.TRUE;
        config.callbackSecret = SECRET;
        config.sandboxId = "sandbox-1";

        var configStore = mock(OcgConfigStore.class);
        when(configStore.loadByChannelId(CHANNEL_ID)).thenReturn(config);

        var remote = mock(Sandbox.class);
        when(remote.getStatus()).thenReturn(SandboxStatus.READY);
        when(remote.ip()).thenReturn("127.0.0.1");
        when(remote.port()).thenReturn(sandboxPort);

        var sandboxService = mock(SandboxService.class);
        when(sandboxService.getSandbox("ocg-" + OCG_CONFIG_ID)).thenReturn(null);
        when(sandboxService.attachSandbox(eq("sandbox-1"), any(SandboxConfig.class), eq("ocg-" + OCG_CONFIG_ID), eq("system"), anyBoolean()))
                .thenReturn(remote);

        var adapter = new OpenClawOutboundAdapter();
        adapter.ocgConfigStore = configStore;
        adapter.sandboxService = sandboxService;
        var ocgSandboxService = mock(OcgSandboxService.class);
        when(ocgSandboxService.buildSandboxConfig()).thenReturn(new SandboxConfig());
        adapter.ocgSandboxService = ocgSandboxService;

        adapter.sendMessage(ChannelMessage.text("hi"), CHANNEL_ID, "qqbot:c2c:OPENID", "qqbot:c2c:OPENID", null, Map.of());

        assertEquals(1, captured.size(), "send must go out after attaching the sandbox on demand");
    }

    @Test
    void platformFailureIsLoggedNotThrown() {
        var adapter = adapter(channelsJson("qqbot"), true, SECRET);
        responseStatus = 502;
        responseBody = "{\"ok\":false,\"code\":\"PLATFORM_SEND_FAILED\",\"message\":\"window exceeded\"}";

        assertDoesNotThrow(() -> adapter.sendMessage(ChannelMessage.text("hi"), CHANNEL_ID,
                "qqbot:c2c:OPENID", "qqbot:c2c:OPENID", null, Map.of()));
        assertEquals(1, captured.size());
    }

    @Test
    void legacySendTextWithoutChannelIdDoesNotSend() {
        var adapter = adapter(channelsJson("qqbot"), true, SECRET);

        adapter.sendText(null, "qqbot:c2c:OPENID", "hi", null, Map.of());

        assertTrue(captured.isEmpty());
    }

    // ---- helpers ----

    private OpenClawOutboundAdapter adapter(String configJson, boolean enabled, String secret) {
        return adapter(configJson, enabled, secret, true);
    }

    private OpenClawOutboundAdapter adapter(String configJson, boolean enabled, String secret, boolean withSandbox) {
        var config = new OcgConfigView();
        config.id = OCG_CONFIG_ID;
        config.channelId = CHANNEL_ID;
        config.configJson = configJson;
        config.enabled = enabled;
        config.callbackSecret = secret;
        config.sandboxId = "sandbox-1";

        var configStore = mock(OcgConfigStore.class);
        when(configStore.loadByChannelId(CHANNEL_ID)).thenReturn(config);

        var sandboxService = mock(SandboxService.class);
        if (withSandbox) {
            var sandbox = mock(Sandbox.class);
            when(sandbox.getStatus()).thenReturn(SandboxStatus.READY);
            when(sandbox.ip()).thenReturn("127.0.0.1");
            when(sandbox.port()).thenReturn(sandboxPort);
            when(sandboxService.getSandbox("ocg-" + OCG_CONFIG_ID)).thenReturn(sandbox);
        }

        var ocgSandboxService = mock(OcgSandboxService.class);
        when(ocgSandboxService.buildSandboxConfig()).thenReturn(new SandboxConfig());

        var adapter = new OpenClawOutboundAdapter();
        adapter.ocgConfigStore = configStore;
        adapter.sandboxService = sandboxService;
        adapter.ocgSandboxService = ocgSandboxService;
        return adapter;
    }

    private String channelsJson(String... channels) {
        var sections = new StringBuilder(32);
        for (var channel : channels) {
            if (sections.length() > 0) sections.append(',');
            sections.append('"').append(channel).append("\":{\"enabled\":true}");
        }
        return "{\"agentUrl\":\"http://agent\",\"async\":true,\"channels\":{" + sections + "}}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asJson(String json) {
        return (Map<String, Object>) JsonUtil.fromJson(Map.class, json);
    }

    private String hmac(byte[] body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One captured gateway request (path, signature header, raw body). */
    private record Captured(String path, String signature, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
