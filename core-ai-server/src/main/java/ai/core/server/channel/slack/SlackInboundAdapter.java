package ai.core.server.channel.slack;

import ai.core.server.channel.ChannelInboundAdapter;
import ai.core.server.channel.InboundEvent;
import core.framework.json.JSON;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class SlackInboundAdapter implements ChannelInboundAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlackInboundAdapter.class);
    private static final Pattern TOOL_DECISION_PATTERN = Pattern.compile("(?i)^\\s*(approve|allow|deny|reject)\\s+(\\S+)");
    private static final int MAX_TIMESTAMP_DRIFT_SECONDS = 300;

    @Override
    public String type() {
        return "slack";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Response> handleChallenge(Request request, Map<String, String> config) {
        var body = bodyAsString(request);
        if (body.isBlank()) return Optional.empty();
        Map<String, Object> payload;
        try {
            payload = (Map<String, Object>) JSON.fromJSON(Map.class, body);
        } catch (Exception e) {
            return Optional.empty();
        }
        if ("url_verification".equals(payload.get("type"))) {
            var challenge = payload.get("challenge");
            if (challenge != null) {
                LOGGER.info("slack url verification challenge received");
                return Optional.of(Response.text(JSON.toJSON(Map.of("challenge", challenge))));
            }
        }
        return Optional.empty();
    }

    @Override
    public void verify(Request request, Map<String, String> config) {
        var signingSecret = config.get("slack_signing_secret");
        if (signingSecret == null || signingSecret.isBlank()) {
            LOGGER.warn("slack_signing_secret not configured — skipping signature verification");
            return;
        }

        var timestampOpt = request.header("X-Slack-Request-Timestamp");
        var signatureOpt = request.header("X-Slack-Signature");
        if (timestampOpt.isEmpty() || signatureOpt.isEmpty()) {
            throw new ForbiddenException("missing slack signature headers");
        }

        var timestamp = timestampOpt.get();
        var signature = signatureOpt.get();

        // Replay protection
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new ForbiddenException("invalid slack timestamp: " + e.getMessage(), "INVALID_SLACK_TIMESTAMP", e);
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > MAX_TIMESTAMP_DRIFT_SECONDS) {
            throw new ForbiddenException("slack timestamp too old");
        }

        var bodyStr = bodyAsString(request);
        var basestring = "v0:" + timestamp + ":" + bodyStr;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var expected = "v0=" + bytesToHex(mac.doFinal(basestring.getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
                throw new ForbiddenException("invalid slack signature");
            }
        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("failed to compute slack signature", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public InboundEvent parseEvent(Request request, Map<String, String> config) {
        var body = bodyAsString(request);
        if (body.isBlank()) return null;

        Map<String, Object> payload;
        try {
            payload = (Map<String, Object>) JSON.fromJSON(Map.class, body);
        } catch (Exception e) {
            LOGGER.warn("failed to parse slack payload", e);
            return null;
        }

        // Skip non-event-callback payloads
        if (!"event_callback".equals(payload.get("type"))) return null;

        var eventWrapper = (Map<String, Object>) payload.get("event");
        if (eventWrapper == null) return null;

        // Skip bot messages and subtypes we don't handle
        if (isBotMessage(eventWrapper)) return null;
        if (isIgnoredSubtype(eventWrapper)) return null;

        var event = new InboundEvent();

        // Extract user
        event.channelUserId = stringField(eventWrapper, "user");
        if (event.channelUserId == null) return null;

        // Extract conversation
        event.conversationId = stringField(eventWrapper, "channel");
        if (event.conversationId == null) return null;

        // Extract thread
        event.threadId = stringField(eventWrapper, "thread_ts");

        // Extract text — check for app_mention and strip the bot mention
        var text = stringField(eventWrapper, "text");
        if (text == null) return null;
        if ("app_mention".equals(stringField(eventWrapper, "type"))) {
            text = stripBotMention(text);
        }
        if (text.isBlank()) return null;
        event.messageText = text.trim();

        // Detect tool approval intent
        detectToolDecision(event);

        return event;
    }

    private void detectToolDecision(InboundEvent event) {
        if (event.messageText == null) return;
        var matcher = TOOL_DECISION_PATTERN.matcher(event.messageText);
        if (matcher.find()) {
            event.commandType = "tool_decision";
            event.toolDecision = matcher.group(1).toLowerCase(Locale.ROOT);
            if ("allow".equals(event.toolDecision)) event.toolDecision = "approve";
            if ("reject".equals(event.toolDecision)) event.toolDecision = "deny";
            event.toolCallId = matcher.group(2);
            LOGGER.info("detected tool decision in slack message: {} {}", event.toolDecision, event.toolCallId);
        }
    }

    private boolean isBotMessage(Map<String, Object> event) {
        var botId = event.get("bot_id");
        if (botId != null) return true;
        var subtype = stringField(event, "subtype");
        return "bot_message".equals(subtype);
    }

    private boolean isIgnoredSubtype(Map<String, Object> event) {
        var subtype = stringField(event, "subtype");
        if (subtype == null) return false;
        // Always ignore these Slack subtypes
        return "message_changed".equals(subtype)
                || "message_deleted".equals(subtype)
                || "channel_join".equals(subtype)
                || "channel_leave".equals(subtype);
    }

    private String stripBotMention(String text) {
        // Remove the leading <@UXXXXX> mention
        return text.replaceFirst("^<@[A-Z0-9]+>\\s*", "");
    }

    private String stringField(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value instanceof String s ? s : null;
    }

    private String bodyAsString(Request request) {
        var body = request.body();
        if (body.isEmpty()) return "";
        return new String(body.get(), StandardCharsets.UTF_8);
    }

    private String bytesToHex(byte[] bytes) {
        var hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
