package ai.core.server.trigger;

import ai.core.server.trigger.action.RunAgentAction;
import ai.core.server.trigger.action.TriggerAction;
import ai.core.server.trigger.domain.Trigger;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.web.Controller;
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
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
public class TriggerController implements Controller {
    private static final Logger LOGGER = LoggerFactory.getLogger(TriggerController.class);

    @Inject
    TriggerService triggerService;

    @Inject
    RunAgentAction runAgentAction;

    @Override
    public Response execute(Request request) {
        var triggerId = request.pathParam("id");
        var bodyStr = bodyAsString(request);

        // 1. Parse payload
        Map<String, Object> payload = parsePayload(bodyStr);

        // 2. Slack URL verification challenge
        if (payload != null && "url_verification".equals(payload.get("type"))) {
            var challenge = payload.get("challenge");
            if (challenge != null) {
                LOGGER.debug("slack url verification challenge received for triggerId={}", triggerId);
                return Response.text(JSON.toJSON(Map.of("challenge", challenge)));
            }
        }

        // 3. Load trigger
        var trigger = triggerService.getEntity(triggerId);

        // 4. Check enabled
        if (!Boolean.TRUE.equals(trigger.enabled)) {
            LOGGER.warn("trigger {} is disabled, rejecting request", triggerId);
            throw new ForbiddenException("trigger is disabled");
        }

        // 5. Verify webhook secret
        verifySecret(request, trigger);

        // 6. Execute action
        var action = resolveAction(trigger);
        var result = action.execute(trigger, bodyStr);

        // 7. Update last triggered timestamp
        trigger.lastTriggeredAt = ZonedDateTime.now();
        // Use a minimal update approach - just track it in logs for now
        LOGGER.info("webhook trigger executed, triggerId={}, action={}, runId={}, status={}",
                triggerId, trigger.actionType, result.runId, result.status);

        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("status", result.status);
        if (result.runId != null) {
            response.put("run_id", result.runId);
        }
        return Response.text(JSON.toJSON(response));
    }

    private String bodyAsString(Request request) {
        var body = request.body();
        if (body.isEmpty()) return "";
        return new String(body.get(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String bodyStr) {
        if (bodyStr == null || bodyStr.isBlank()) return null;
        try {
            return (Map<String, Object>) JSON.fromJSON(Map.class, bodyStr);
        } catch (Exception e) {
            return null;
        }
    }

    private void verifySecret(Request request, Trigger trigger) {
        var config = trigger.config;
        if (config == null) return;

        var verifierType = config.getOrDefault("verifier_type", "bearer");
        if ("bearer".equals(verifierType)) {
            var secret = config.get("secret");
            if (secret == null || secret.isBlank()) {
                // No secret configured — skip verification
                return;
            }
            var auth = request.header("Authorization");
            if (auth.isEmpty() || !auth.get().startsWith("Bearer ")) {
                throw new ForbiddenException("missing or invalid authorization header");
            }
            var token = auth.get().substring(7);
            if (!secret.equals(token)) {
                throw new ForbiddenException("invalid webhook secret");
            }
        } else if ("slack".equals(verifierType)) {
            verifySlackSignature(request, trigger);
        } else {
            LOGGER.warn("unsupported verifier_type={} for triggerId={}", verifierType, trigger.id);
        }
    }

    private void verifySlackSignature(Request request, Trigger trigger) {
        var signingSecret = trigger.config.get("slack_signing_secret");
        if (signingSecret == null || signingSecret.isBlank()) {
            LOGGER.warn("slack_signing_secret not configured for triggerId={}", trigger.id);
            throw new ForbiddenException("slack signing secret not configured");
        }

        var timestampOpt = request.header("X-Slack-Request-Timestamp");
        var signatureOpt = request.header("X-Slack-Signature");
        if (timestampOpt.isEmpty() || signatureOpt.isEmpty()) {
            throw new ForbiddenException("missing slack headers");
        }
        var timestamp = timestampOpt.get();
        var signature = signatureOpt.get();

        // Replay protection: timestamp must be within 5 minutes
        long now = Instant.now().getEpochSecond();
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new ForbiddenException("invalid slack timestamp", "INVALID_SLACK_TIMESTAMP", e);
        }
        if (Math.abs(now - ts) > 300) {
            throw new ForbiddenException("slack timestamp too old");
        }

        // Compute signature: HMAC-SHA256 of "v0:{timestamp}:{body}"
        var bodyStr = bodyAsString(request);
        var basestring = "v0:" + timestamp + ":" + bodyStr;
        String expectedSignature;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expectedSignature = "v0=" + bytesToHex(mac.doFinal(basestring.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("failed to compute slack signature", e);
        }

        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ForbiddenException("invalid slack signature");
        }
    }

    private String bytesToHex(byte[] bytes) {
        var hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private TriggerAction resolveAction(Trigger trigger) {
        if (trigger.actionType == null) {
            throw new RuntimeException("no action configured for trigger, id=" + trigger.id);
        }
        if ("RUN_AGENT".equals(trigger.actionType)) {
            return runAgentAction;
        }
        throw new RuntimeException("unsupported action type: " + trigger.actionType + " for trigger, id=" + trigger.id);
    }
}
