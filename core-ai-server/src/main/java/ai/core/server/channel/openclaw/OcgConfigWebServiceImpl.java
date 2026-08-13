package ai.core.server.channel.openclaw;

import ai.core.api.server.ocg.ListOcgConfigsResponse;
import ai.core.api.server.ocg.OcgCommandRequest;
import ai.core.api.server.ocg.OcgCommandResponse;
import ai.core.api.server.ocg.OcgConfigRequest;
import ai.core.api.server.ocg.OcgConfigResponse;
import ai.core.api.server.ocg.OcgConfigView;
import ai.core.api.server.ocg.OcgConfigWebService;
import ai.core.api.server.ocg.OcgLogsRequest;
import ai.core.api.server.ocg.OcgLogsResponse;
import ai.core.api.server.ocg.OcgStatusResponse;
import ai.core.server.channel.ChannelConfigStore;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class OcgConfigWebServiceImpl implements OcgConfigWebService {
    private static final String OPENCLAW_CHANNEL_TYPE = "openclaw";

    @Inject
    OcgConfigStore ocgConfigStore;
    @Inject
    ChannelConfigStore channelConfigStore;
    @Inject
    OcgSandboxService ocgSandboxService;

    @Override
    public ListOcgConfigsResponse list() {
        var response = new ListOcgConfigsResponse();
        response.configs = ocgConfigStore.all().values().stream().map(this::toView).toList();
        return response;
    }

    @Override
    public OcgConfigResponse create(OcgConfigRequest request) {
        var id = request.id;
        if (id == null || id.isBlank()) throw new BadRequestException("id is required");
        if (ocgConfigStore.load(id) != null) throw new ConflictException("OCG config already exists: " + id);
        var config = fromRequest(request, id, null);
        var now = ZonedDateTime.now();
        config.createdAt = now;
        config.updatedAt = now;
        ocgConfigStore.store(config);
        return configResponse(config);
    }

    @Override
    public OcgConfigResponse get(String id) {
        return configResponse(load(id));
    }

    @Override
    public OcgConfigResponse update(String id, OcgConfigRequest request) {
        var existing = load(id);
        var config = fromRequest(request, id, existing);
        config.createdAt = existing.createdAt;
        config.sandboxId = existing.sandboxId;
        config.sandboxIp = existing.sandboxIp;
        config.updatedAt = ZonedDateTime.now();
        ocgConfigStore.store(config);
        return configResponse(config);
    }

    @Override
    public void delete(String id) {
        var config = load(id);
        if (config.sandboxId != null && !config.sandboxId.isBlank()) {
            throw new BadRequestException("stop sandbox before deleting OCG config");
        }
        ocgConfigStore.remove(id);
    }

    @Override
    public OcgConfigResponse start(String id) {
        ocgSandboxService.startSandbox(id);
        return configResponse(load(id));
    }

    @Override
    public OcgConfigResponse stop(String id) {
        ocgSandboxService.stopSandbox(id);
        return configResponse(load(id));
    }

    @Override
    public OcgConfigResponse restart(String id) {
        ocgSandboxService.restartGateway(id);
        return configResponse(load(id));
    }

    @Override
    public OcgCommandResponse command(String id, OcgCommandRequest request) {
        ocgSandboxService.runTerminalCommand(id, request.command);
        var response = new OcgCommandResponse();
        response.ok = Boolean.TRUE;
        return response;
    }

    @Override
    public OcgLogsResponse logs(String id, OcgLogsRequest request) {
        var config = load(id);
        var type = request.type != null ? request.type : "gateway";
        var tail = request.tail != null ? request.tail : 300;
        var response = new OcgLogsResponse();
        response.logs = ocgSandboxService.logs(config.id, type, tail);
        return response;
    }

    @Override
    public OcgStatusResponse status(String id) {
        var config = load(id);
        var response = new OcgStatusResponse();
        response.status = ocgSandboxService.getStatus(id);
        response.sandboxId = config.sandboxId;
        response.sandboxIp = config.sandboxIp;
        return response;
    }

    private ai.core.server.channel.openclaw.OcgConfigView fromRequest(OcgConfigRequest request, String id, ai.core.server.channel.openclaw.OcgConfigView existing) {
        var channelId = request.channelId;
        if (channelId == null || channelId.isBlank()) throw new BadRequestException("channelId is required");
        validateChannel(channelId);
        var configJson = request.configJson;
        if (configJson == null || configJson.isBlank()) throw new BadRequestException("configJson is required");
        var config = new ai.core.server.channel.openclaw.OcgConfigView();
        config.id = id;
        config.channelId = channelId;
        config.configJson = configJson;
        config.callbackSecret = request.callbackSecret;
        config.enabled = request.enabled != null ? request.enabled : existing == null || !Boolean.FALSE.equals(existing.enabled);
        return config;
    }

    private void validateChannel(String channelId) {
        var channel = channelConfigStore.load(channelId);
        if (channel == null) throw new BadRequestException("channel not found: " + channelId);
        if (!OPENCLAW_CHANNEL_TYPE.equals(channel.channelType)) throw new BadRequestException("channel " + channelId + " is not openclaw");
    }

    private ai.core.server.channel.openclaw.OcgConfigView load(String id) {
        var config = ocgConfigStore.load(id);
        if (config == null) throw new NotFoundException("OCG config not found: " + id);
        return config;
    }

    private OcgConfigResponse configResponse(ai.core.server.channel.openclaw.OcgConfigView config) {
        var response = new OcgConfigResponse();
        response.config = toView(config);
        return response;
    }

    private OcgConfigView toView(ai.core.server.channel.openclaw.OcgConfigView config) {
        var view = new OcgConfigView();
        view.id = config.id;
        view.channelId = config.channelId;
        view.configJson = config.configJson;
        view.callbackSecret = config.callbackSecret;
        view.enabled = config.enabled;
        view.sandboxId = config.sandboxId;
        view.sandboxIp = config.sandboxIp;
        view.sandboxStatus = ocgSandboxService.getStatus(config.id);
        view.createdAt = config.createdAt;
        view.updatedAt = config.updatedAt;
        var channel = channelConfigStore.load(config.channelId);
        view.channelName = channel != null ? channel.channelId : config.channelId;
        return view;
    }
}
