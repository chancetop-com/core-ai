package ai.core.server.channel;

import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    public Response list(Request request) {
        var channels = new ArrayList<ChannelConfigView>();
        for (var entry : configStore.all().entrySet()) {
            channels.add(entry.getValue());
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
        configStore.store(view);
        LOGGER.info("channel created, channelId={}, type={}", channelId, view.channelType);
        var resp = JSON.toJSON(Map.of("channel", view));
        return Response.text(resp);
    }

    public Response get(Request request) {
        var channelId = request.pathParam("channelId");
        var view = configStore.load(channelId);
        if (view == null) throw new NotFoundException("channel not found: " + channelId);
        var resp = JSON.toJSON(Map.of("channel", view));
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
        configStore.store(view);
        LOGGER.info("channel updated, channelId={}, type={}", channelId, view.channelType);
        var resp = JSON.toJSON(Map.of("channel", view));
        return Response.text(resp);
    }

    public Response delete(Request request) {
        var channelId = request.pathParam("channelId");
        if (configStore.load(channelId) == null) throw new NotFoundException("channel not found: " + channelId);
        configStore.remove(channelId);
        LOGGER.info("channel deleted, channelId={}", channelId);
        return Response.text("{\"ok\":true}");
    }

    @SuppressWarnings("unchecked")
    private ChannelConfigView fromPayload(Map<String, Object> payload, String channelId) {
        var view = new ChannelConfigView();
        view.channelId = channelId;
        view.channelType = (String) payload.getOrDefault("channelType", "slack");
        view.enabled = payload.containsKey("enabled") ? Boolean.TRUE.equals(payload.get("enabled")) : true;
        view.agentId = (String) payload.get("agentId");
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

    /** Returns available channel types that have registered adapters. */
    public Response types(Request request) {
        var types = List.of(
            Map.of("type", "slack", "label", "Slack"),
            Map.of("type", "telegram", "label", "Telegram"),
            Map.of("type", "weclaw", "label", "WeClaw (WeChat)")
        );
        var resp = JSON.toJSON(Map.of("types", types));
        return Response.text(resp);
    }
}
