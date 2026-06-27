package ai.core.server.channel.openclaw;

import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.session.ChatMessageService;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class OcgCallbackPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(OcgCallbackPool.class);
    private static final long POLL_TIMEOUT_MS = 30 * 60 * 1000;
    private static final long POLL_INTERVAL_MS = 500;

    private final ExecutorService executor = new ThreadPoolExecutor(
            8, 32, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128),
            new ThreadPoolExecutor.CallerRunsPolicy());
    private final HTTPClient httpClient = HTTPClient.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .timeout(Duration.ofSeconds(30))
            .build();

    @Inject
    ChatMessageService chatMessageService;
    @Inject
    OcgConfigStore ocgConfigStore;
    @Inject
    SandboxService sandboxService;

    public void submit(String sessionId, String callbackUrl, String channelId, int initialMessageCount) {
        executor.submit(() -> callback(sessionId, callbackUrl, channelId, initialMessageCount));
    }

    private void callback(String sessionId, String callbackUrl, String channelId, int initialMessageCount) {
        var reply = pollForResponse(sessionId, initialMessageCount);
        var config = ocgConfigStore.loadByChannelId(channelId);
        var callbackSecret = config == null ? null : config.callbackSecret;
        var resolvedCallbackUrl = resolveCallbackUrl(callbackUrl, config);
        if (reply != null) {
            postCallback(resolvedCallbackUrl, reply, false, callbackSecret);
        } else {
            postCallback(resolvedCallbackUrl, "Agent did not respond within timeout", true, callbackSecret);
        }
    }

    private String pollForResponse(String sessionId, int initialMessageCount) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            var messages = chatMessageService.history(sessionId);
            for (int i = initialMessageCount; i < messages.size(); i++) {
                var msg = messages.get(i);
                if ("agent".equals(msg.role) && msg.content != null && !msg.content.isBlank()) {
                    return msg.content;
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private String resolveCallbackUrl(String callbackUrl, OcgConfigView config) {
        if (config == null || config.sandboxId == null || config.sandboxId.isBlank()) return callbackUrl;
        URI uri;
        try {
            uri = URI.create(callbackUrl);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("invalid OCG callback url, url={}", callbackUrl);
            return callbackUrl;
        }
        var port = uri.getPort();
        var host = uri.getHost();
        if (port != 3457 || !isLocalCallbackHost(host)) return callbackUrl;
        var sandbox = sandboxService.getSandbox(sandboxSessionId(config.id));
        if (sandbox == null || sandbox.getStatus() == SandboxStatus.TERMINATED || sandbox.getStatus() == SandboxStatus.ERROR) {
            LOGGER.warn("OCG sandbox unavailable while resolving callback url, configId={}, sandboxId={}, url={}", config.id, config.sandboxId, callbackUrl);
            return callbackUrl;
        }
        var resolved = "http://" + sandbox.ip() + ":" + sandbox.port() + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        LOGGER.info("rewrote OCG callback url via sandbox runtime, original={}, resolved={}", callbackUrl, resolved);
        return resolved;
    }

    private boolean isLocalCallbackHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "0.0.0.0".equals(host);
    }

    private String sandboxSessionId(String id) {
        return "ocg-" + id;
    }

    private void postCallback(String url, String reply, boolean isError, String secret) {
        var body = new LinkedHashMap<String, Object>();
        body.put("reply", reply);
        body.put("isError", isError);
        var bodyJson = JsonUtil.toJson(body);
        var bodyBytes = bodyJson.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var request = new HTTPRequest(HTTPMethod.POST, url);
                request.body(bodyBytes, ContentType.APPLICATION_JSON);
                if (secret != null && !secret.isBlank()) {
                    request.headers.put("X-OCG-Signature", "sha256=" + hmacSha256(bodyBytes, secret));
                }
                var response = httpClient.execute(request);
                if (response.statusCode >= 200 && response.statusCode < 300) {
                    LOGGER.info("OCG callback success, url={}, attempt={}", url, attempt);
                    return;
                }
                throw new RuntimeException("HTTP " + response.statusCode + ": " + response.text());
            } catch (Exception e) {
                if (attempt == 3) {
                    LOGGER.error("OCG callback failed after 3 attempts, url={}, replyLen={}", url, reply.length(), e);
                } else {
                    sleep(2000L * attempt);
                }
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String hmacSha256(byte[] bodyBytes, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(bodyBytes));
        } catch (Exception e) {
            throw new RuntimeException("failed to sign OCG callback", e);
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
