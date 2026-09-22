package ai.core.server.channel.openclaw;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.channel.ChannelMessage;
import ai.core.server.channel.ChannelOutboundAdapter;
import ai.core.server.sandbox.SandboxService;
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
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proactive send to channels hosted by the OpenClaw Channel Gateway (OCG) running in a
 * per-channel sandbox.
 *
 * <p>Inbound traffic goes OCG → core-ai (OpenAI-compatible sync endpoint / OCG callback).
 * This adapter is the opposite direction: core-ai asks the gateway to deliver a message
 * that no inbound message triggered (scheduled runs, cost alerts).
 *
 * <p>Routing: the recipient id carries the gateway target in OCG's own normalized form
 * (e.g. {@code qqbot:c2c:OPENID}, {@code qqbot:group:123456}); its prefix selects the OCG
 * channel inside the gateway config, and the core-ai channel id resolves the gateway config
 * plus its sandbox. Delivery goes to {@code http://<sandbox-ip>:<sandbox-port>/ocg/send},
 * which the sandbox runtime proxies to the gateway's loopback port, signed with the same
 * HMAC-SHA256 scheme (and secret) OCG uses for callbacks.
 *
 * @author stephen
 */
public class OpenClawOutboundAdapter implements ChannelOutboundAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenClawOutboundAdapter.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final String OCG_CHANNEL_TYPE = "openclaw";
    private static final String SEND_PATH = "/ocg/send";
    private static final String SIGNATURE_HEADER = "X-OCG-Signature";
    private static final int MAX_LOG_BODY = 300;
    /** OCG tracks its sandbox session as "ocg-{configId}" (see OcgSandboxService/OcgCallbackPool). */
    private static final String SANDBOX_SESSION_PREFIX = "ocg-";

    @Inject
    OcgConfigStore ocgConfigStore;
    @Inject
    SandboxService sandboxService;
    /** Only used for {@link OcgSandboxService#buildSandboxConfig()} when attaching on demand. */
    @Inject
    OcgSandboxService ocgSandboxService;

    HTTPClient httpClient = new PatchedHTTPClientBuilder()
            .timeout(HTTP_TIMEOUT)
            .build();

    @Override
    public String type() {
        return OCG_CHANNEL_TYPE;
    }

    /**
     * Legacy 5-argument send has no channel id, so the gateway config cannot be resolved.
     * Callers that own the channel (AgentRunner, CostAlertService) use the channel-aware
     * overload below.
     */
    @Override
    public void sendText(String channelUserId, String conversationId, String text,
                          String threadId, Map<String, String> config) {
        LOGGER.warn("openclaw send requires a channel id, use sendMessage(message, channelId, ...) instead");
    }

    @Override
    public void sendMessage(ChannelMessage message, String channelId, String channelUserId,
                             String conversationId, String threadId, Map<String, String> config) {
        if (message == null) return;
        var target = firstNonBlank(conversationId, channelUserId);
        if (target == null) {
            LOGGER.warn("openclaw send skipped, channelId={}, no recipient", channelId);
            return;
        }

        var ocgConfig = ocgConfigStore.loadByChannelId(channelId);
        if (ocgConfig == null) {
            LOGGER.warn("openclaw send skipped, no OCG config for channelId={}", channelId);
            return;
        }
        if (!Boolean.TRUE.equals(ocgConfig.enabled)) {
            LOGGER.warn("openclaw send skipped, OCG config disabled, id={}", ocgConfig.id);
            return;
        }

        var ocgChannel = resolveOcgChannel(target, ocgConfig.configJson);
        if (ocgChannel == null) {
            LOGGER.warn("openclaw send skipped, cannot resolve gateway channel for target={}, ocgConfigId={}",
                    target, ocgConfig.id);
            return;
        }

        var sandbox = resolveSandbox(ocgConfig);
        if (sandbox == null) {
            LOGGER.warn("openclaw send skipped, sandbox unavailable, ocgConfigId={}, start the OpenClaw sandbox first",
                    ocgConfig.id);
            return;
        }

        var payload = buildPayload(ocgChannel, target, message, threadId);
        try {
            post(sandbox.ip(), sandbox.port(), JsonUtil.toJson(payload), ocgConfig.callbackSecret);
        } catch (Exception e) {
            LOGGER.warn("openclaw send failed, channelId={}, ocgConfigId={}, target={}", channelId, ocgConfig.id, target, e);
        }
    }

    /**
     * Sandbox hosting the config, preferring the one attached to this replica and
     * attaching on demand otherwise — a send can be triggered on a replica that has
     * not seen the sandbox yet (same pattern as OcgSandboxService's own resolution).
     */
    private Sandbox resolveSandbox(OcgConfigView config) {
        if (config.sandboxId == null || config.sandboxId.isBlank()) return null;
        var sessionId = SANDBOX_SESSION_PREFIX + config.id;
        var local = sandboxService.getSandbox(sessionId);
        if (local != null && usable(local)) return local;
        try {
            var attached = sandboxService.attachSandbox(config.sandboxId, ocgSandboxService.buildSandboxConfig(),
                    sessionId, "system", true);
            if (attached != null && usable(attached)) {
                LOGGER.info("OCG sandbox attached for send, ocgConfigId={}, sandboxId={}", config.id, config.sandboxId);
                return attached;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("OCG sandbox attach failed for send, ocgConfigId={}: {}", config.id, e.getMessage());
        }
        return null;
    }

    private boolean usable(Sandbox sandbox) {
        return sandbox.getStatus() != SandboxStatus.TERMINATED && sandbox.getStatus() != SandboxStatus.ERROR
                && sandbox.ip() != null && !sandbox.ip().isBlank();
    }

    Map<String, Object> buildPayload(String ocgChannel, String target, ChannelMessage message, String threadId) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("channel", ocgChannel);
        payload.put("to", target);
        if (message.text != null && !message.text.isBlank()) payload.put("text", message.text);
        if (message.mediaUrl != null && !message.mediaUrl.isBlank()) payload.put("mediaUrl", message.mediaUrl);
        if (threadId != null && !threadId.isBlank()) payload.put("replyToId", threadId);
        if (message.custom != null) {
            for (var entry : message.custom.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) payload.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return payload;
    }

    /**
     * Resolve the gateway channel for a target: OCG targets are prefixed with their channel
     * name ({@code qqbot:c2c:...}). Targets without a usable prefix (e.g. plain Telegram chat
     * ids) fall back to the only channel configured in the gateway when it is unambiguous.
     */
    String resolveOcgChannel(String target, String configJson) {
        var channels = configuredChannels(configJson);
        if (channels.isEmpty()) return null;

        var prefix = target.contains(":") ? target.substring(0, target.indexOf(':')) : null;
        if (prefix != null && channels.contains(prefix)) return prefix;
        return channels.size() == 1 ? channels.get(0) : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> configuredChannels(String configJson) {
        if (configJson == null || configJson.isBlank()) return List.of();
        try {
            var parsed = (Map<String, Object>) JsonUtil.fromJson(Map.class, configJson);
            if (parsed.get("channels") instanceof Map<?, ?> channels) {
                return channels.keySet().stream()
                        .filter(key -> key instanceof String name && !name.isBlank() && !name.startsWith("_"))
                        .map(String::valueOf)
                        .toList();
            }
        } catch (Exception e) {
            LOGGER.warn("failed to parse OCG config channels: {}", e.getMessage());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private void post(String sandboxIp, int sandboxPort, String bodyJson, String secret) {
        var url = "http://" + sandboxIp + ":" + sandboxPort + SEND_PATH;
        var request = new HTTPRequest(HTTPMethod.POST, url);
        var body = bodyJson.getBytes(StandardCharsets.UTF_8);
        request.body(body, ContentType.APPLICATION_JSON);
        if (secret != null && !secret.isBlank()) {
            request.headers.put(SIGNATURE_HEADER, "sha256=" + hmacSha256(body, secret));
        }

        var response = httpClient.execute(request);
        var responseText = response.text();
        if (response.statusCode < 200 || response.statusCode >= 300) {
            LOGGER.warn("openclaw send rejected, url={}, status={}, body={}",
                    url, response.statusCode, truncate(responseText));
            return;
        }
        if (responseText == null || responseText.isBlank()) return;

        try {
            var result = (Map<String, Object>) JsonUtil.fromJson(Map.class, responseText);
            LOGGER.info("openclaw message sent, target={}, chunks={}, messageId={}",
                    result.get("to"), result.get("chunks"), result.get("messageId"));
        } catch (RuntimeException e) {
            LOGGER.info("openclaw message sent, response={}", truncate(responseText));
        }
    }

    private String hmacSha256(byte[] body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("failed to sign OCG send request", e);
        }
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() <= MAX_LOG_BODY ? text : text.substring(0, MAX_LOG_BODY) + "...";
    }
}
