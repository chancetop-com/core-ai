package ai.core.server.channel.openclaw;

import ai.core.server.channel.ChannelConfigStore;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class OcgConfigController {
    private static final String OPENCLAW_CHANNEL_TYPE = "openclaw";

    @Inject
    OcgConfigStore ocgConfigStore;
    @Inject
    ChannelConfigStore channelConfigStore;
    @Inject
    OcgSandboxService ocgSandboxService;

    public Response list(Request request) {
        var configs = new ArrayList<Map<String, Object>>();
        for (var config : ocgConfigStore.all().values()) {
            configs.add(toMap(config));
        }
        return json(Map.of("configs", configs));
    }

    @SuppressWarnings("unchecked")
    public Response create(Request request) {
        var payload = readPayload(request);
        var id = (String) payload.get("id");
        if (id == null || id.isBlank()) throw new BadRequestException("id is required");
        if (ocgConfigStore.load(id) != null) throw new ConflictException("OCG config already exists: " + id);
        var config = fromPayload(payload, id, null);
        var now = ZonedDateTime.now();
        config.createdAt = now;
        config.updatedAt = now;
        ocgConfigStore.store(config);
        return json(Map.of("config", toMap(config)));
    }

    public Response get(Request request) {
        var config = load(request.pathParam("id"));
        return json(Map.of("config", toMap(config)));
    }

    public Response update(Request request) {
        var id = request.pathParam("id");
        var existing = load(id);
        var payload = readPayload(request);
        var config = fromPayload(payload, id, existing);
        config.createdAt = existing.createdAt;
        config.sandboxId = existing.sandboxId;
        config.sandboxIp = existing.sandboxIp;
        config.updatedAt = ZonedDateTime.now();
        ocgConfigStore.store(config);
        return json(Map.of("config", toMap(config)));
    }

    public Response delete(Request request) {
        var id = request.pathParam("id");
        var config = load(id);
        if (config.sandboxId != null && !config.sandboxId.isBlank()) {
            throw new BadRequestException("stop sandbox before deleting OCG config");
        }
        ocgConfigStore.remove(id);
        return Response.text("{\"ok\":true}");
    }

    public Response start(Request request) {
        var id = request.pathParam("id");
        ocgSandboxService.startSandbox(id);
        return json(Map.of("config", toMap(load(id))));
    }

    public Response stop(Request request) {
        var id = request.pathParam("id");
        ocgSandboxService.stopSandbox(id);
        return json(Map.of("config", toMap(load(id))));
    }

    public Response status(Request request) {
        var id = request.pathParam("id");
        var config = load(id);
        var body = new LinkedHashMap<String, Object>();
        body.put("status", ocgSandboxService.getStatus(id));
        body.put("sandboxId", config.sandboxId);
        body.put("sandboxIp", config.sandboxIp);
        return json(body);
    }

    private OcgConfigView fromPayload(Map<String, Object> payload, String id, OcgConfigView existing) {
        var channelId = (String) payload.get("channelId");
        if (channelId == null || channelId.isBlank()) throw new BadRequestException("channelId is required");
        validateChannel(channelId);
        var configJson = (String) payload.get("configJson");
        if (configJson == null || configJson.isBlank()) throw new BadRequestException("configJson is required");
        validateConfigJson(configJson);
        var config = new OcgConfigView();
        config.id = id;
        config.channelId = channelId;
        config.configJson = configJson;
        config.callbackSecret = (String) payload.get("callbackSecret");
        config.enabled = payload.containsKey("enabled") ? Boolean.TRUE.equals(payload.get("enabled")) : existing == null || !Boolean.FALSE.equals(existing.enabled);
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(Request request) {
        var body = request.body();
        if (body.isEmpty()) throw new BadRequestException("body is required");
        return (Map<String, Object>) JSON.fromJSON(Map.class, new String(body.get(), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private void validateConfigJson(String configJson) {
        try {
            var parsed = JsonUtil.fromJson(Map.class, configJson);
            if (!(parsed instanceof Map)) throw new BadRequestException("configJson must be a JSON object");
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("invalid configJson: " + e.getMessage());
        }
    }

    private void validateChannel(String channelId) {
        var channel = channelConfigStore.load(channelId);
        if (channel == null) throw new BadRequestException("channel not found: " + channelId);
        if (!OPENCLAW_CHANNEL_TYPE.equals(channel.channelType)) throw new BadRequestException("channel " + channelId + " is not openclaw");
    }

    private OcgConfigView load(String id) {
        var config = ocgConfigStore.load(id);
        if (config == null) throw new NotFoundException("OCG config not found: " + id);
        return config;
    }

    private Map<String, Object> toMap(OcgConfigView config) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", config.id);
        map.put("channelId", config.channelId);
        map.put("configJson", config.configJson);
        map.put("callbackSecret", config.callbackSecret);
        map.put("enabled", config.enabled);
        map.put("sandboxId", config.sandboxId);
        map.put("sandboxIp", config.sandboxIp);
        map.put("sandboxStatus", ocgSandboxService.getStatus(config.id));
        map.put("createdAt", config.createdAt);
        map.put("updatedAt", config.updatedAt);
        var channel = channelConfigStore.load(config.channelId);
        map.put("channelName", channel != null ? channel.channelId : config.channelId);
        return map;
    }

    private Response json(Object body) {
        return Response.text(JSON.toJSON(body));
    }
}
