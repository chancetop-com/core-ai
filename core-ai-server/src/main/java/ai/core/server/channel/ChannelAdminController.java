package ai.core.server.channel;

import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class ChannelAdminController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelAdminController.class);

    @Inject
    ChannelConfigStore configStore;

    @Inject
    ChannelRegistry channelRegistry;

    @Inject
    WebContext webContext;

    public Response list(Request request) {
        var baseUrl = baseUrl(request);
        var channels = new ArrayList<Map<String, Object>>();
        for (var entry : configStore.all().entrySet()) {
            channels.add(toChannelMap(entry.getValue(), baseUrl));
        }
        var body = JSON.toJSON(Map.of("channels", channels));
        return Response.text(body);
    }

    @SuppressWarnings("unchecked")
    public Response create(Request request) {
        var body = request.body();
        if (body.isEmpty()) throw new BadRequestException("body is required");
        var payload = (Map<String, Object>) JSON.fromJSON(Map.class, new String(body.get(), StandardCharsets.UTF_8));

        var channelId = (String) payload.get("channelId");
        if (channelId == null || channelId.isBlank()) throw new BadRequestException("channelId is required");
        if (configStore.load(channelId) != null) throw new BadRequestException("channel already exists: " + channelId);

        var view = fromPayload(payload, channelId);
        view.userId = AuthContext.userId(webContext);
        configStore.store(view);
        LOGGER.info("channel created, channelId={}, type={}, userId={}", channelId, view.channelType, view.userId);
        var baseUrl = baseUrl(request);
        var resp = JSON.toJSON(Map.of("channel", toChannelMap(view, baseUrl)));
        return Response.text(resp);
    }

    public Response get(Request request) {
        var channelId = request.pathParam("channelId");
        var view = configStore.load(channelId);
        if (view == null) throw new NotFoundException("channel not found: " + channelId);
        var baseUrl = baseUrl(request);
        var resp = JSON.toJSON(Map.of("channel", toChannelMap(view, baseUrl)));
        return Response.text(resp);
    }

    @SuppressWarnings("unchecked")
    public Response update(Request request) {
        var channelId = request.pathParam("channelId");
        var view = configStore.load(channelId);
        if (view == null) throw new NotFoundException("channel not found: " + channelId);

        var body = request.body();
        if (body.isEmpty()) throw new BadRequestException("body is required");
        var payload = (Map<String, Object>) JSON.fromJSON(Map.class, new String(body.get(), StandardCharsets.UTF_8));

        view = fromPayload(payload, channelId);
        if (view.userId == null) view.userId = AuthContext.userId(webContext);
        configStore.store(view);
        LOGGER.info("channel updated, channelId={}, type={}, userId={}", channelId, view.channelType, view.userId);
        var baseUrl = baseUrl(request);
        var resp = JSON.toJSON(Map.of("channel", toChannelMap(view, baseUrl)));
        return Response.text(resp);
    }

    public Response delete(Request request) {
        var channelId = request.pathParam("channelId");
        if (configStore.load(channelId) == null) throw new NotFoundException("channel not found: " + channelId);
        configStore.remove(channelId);
        LOGGER.info("channel deleted, channelId={}", channelId);
        return Response.text("{\"ok\":true}");
    }

    /** Returns available channel types that have registered adapters. */
    public Response types(Request request) {
        var types = List.of(
            Map.of("type", "slack", "label", "Slack"),
            Map.of("type", "telegram", "label", "Telegram"),
            Map.of("type", "weclaw", "label", "WeClaw (WeChat)"),
            Map.of("type", "openclaw", "label", "OpenClaw")
        );
        var resp = JSON.toJSON(Map.of("types", types));
        return Response.text(resp);
    }

    @SuppressWarnings("unchecked")
    private ChannelConfigView fromPayload(Map<String, Object> payload, String channelId) {
        var view = new ChannelConfigView();
        view.channelId = channelId;
        view.channelType = (String) payload.getOrDefault("channelType", "slack");
        view.enabled = payload.containsKey("enabled") ? Boolean.TRUE.equals(payload.get("enabled")) : true;
        view.agentId = (String) payload.get("agentId");
        view.userId = (String) payload.get("userId");
        view.sessionTtlMinutes = payload.get("sessionTtlMinutes") instanceof Number n ? n.intValue() : 60;
        if (payload.containsKey("requireAuth")) {
            view.requireAuth = Boolean.TRUE.equals(payload.get("requireAuth"));
        }
        if (payload.get("config") instanceof Map<?, ?> cfg) {
            view.config = (Map<String, String>) cfg;
        }
        if (payload.get("filterConfig") instanceof Map<?, ?> fcfg) {
            view.filterConfig = (Map<String, String>) fcfg;
        }
        return view;
    }

    private String baseUrl(Request request) {
        var scheme = request.scheme();
        var hostHeader = request.header("Host").orElse(request.hostname());
        return scheme + "://" + hostHeader;
    }

    private String webhookUrl(ChannelConfigView ch, String baseUrl) {
        return baseUrl + "/api/channels/" + ch.channelId + "/v1/chat/completions";
    }

    private Map<String, Object> toChannelMap(ChannelConfigView ch, String baseUrl) {
        var map = new LinkedHashMap<String, Object>();
        map.put("channelId", ch.channelId);
        map.put("channelType", ch.channelType);
        map.put("enabled", ch.enabled);
        map.put("requireAuth", ch.requireAuth);
        map.put("agentId", ch.agentId);
        map.put("userId", ch.userId);
        map.put("sessionTtlMinutes", ch.sessionTtlMinutes);
        map.put("config", ch.config);
        map.put("filterConfig", ch.filterConfig);
        map.put("webhookUrl", webhookUrl(ch, baseUrl));
        return map;
    }
}
