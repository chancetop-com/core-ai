package ai.core.server.channel;

import ai.core.api.server.channel.ChannelConfigRequest;
import ai.core.api.server.channel.ChannelConfigView;
import ai.core.api.server.channel.ChannelResponse;
import ai.core.api.server.channel.ChannelTypeView;
import ai.core.api.server.channel.ChannelWebService;
import ai.core.api.server.channel.ListChannelTypesResponse;
import ai.core.api.server.channel.ListChannelsResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
public class ChannelWebServiceImpl implements ChannelWebService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelWebServiceImpl.class);

    @Inject
    ChannelConfigStore configStore;

    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_VIEW)
    public ListChannelsResponse list() {
        var baseUrl = baseUrl();
        var response = new ListChannelsResponse();
        response.channels = configStore.all().values().stream()
            .map(channel -> toView(channel, baseUrl))
            .toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public ChannelResponse create(ChannelConfigRequest request) {
        var channelId = request.channelId;
        if (channelId == null || channelId.isBlank()) throw new BadRequestException("channelId is required");
        if (configStore.load(channelId) != null) throw new BadRequestException("channel already exists: " + channelId);

        var view = fromRequest(request, channelId);
        view.userId = AuthContext.userId(webContext);
        configStore.store(view);
        LOGGER.info("channel created, channelId={}, type={}, userId={}", channelId, view.channelType, view.userId);
        return channelResponse(view);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_VIEW)
    public ChannelResponse get(String channelId) {
        var view = configStore.load(channelId);
        if (view == null) throw new NotFoundException("channel not found: " + channelId);
        return channelResponse(view);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public ChannelResponse update(String channelId, ChannelConfigRequest request) {
        var existing = configStore.load(channelId);
        if (existing == null) throw new NotFoundException("channel not found: " + channelId);

        var view = fromRequest(request, channelId);
        if (view.userId == null) view.userId = AuthContext.userId(webContext);
        configStore.store(view);
        LOGGER.info("channel updated, channelId={}, type={}, userId={}", channelId, view.channelType, view.userId);
        return channelResponse(view);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public void delete(String channelId) {
        if (configStore.load(channelId) == null) throw new NotFoundException("channel not found: " + channelId);
        configStore.remove(channelId);
        LOGGER.info("channel deleted, channelId={}", channelId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_VIEW)
    public ListChannelTypesResponse types() {
        var types = List.of(
            type("slack", "Slack"),
            type("telegram", "Telegram"),
            type("weclaw", "WeClaw (WeChat)"),
            type("openclaw", "OpenClaw")
        );
        var response = new ListChannelTypesResponse();
        response.types = types;
        return response;
    }

    private ChannelTypeView type(String type, String label) {
        var view = new ChannelTypeView();
        view.type = type;
        view.label = label;
        return view;
    }

    private ai.core.server.channel.ChannelConfigView fromRequest(ChannelConfigRequest request, String channelId) {
        var view = new ai.core.server.channel.ChannelConfigView();
        view.channelId = channelId;
        view.channelType = request.channelType != null ? request.channelType : "slack";
        view.mode = request.mode != null ? request.mode : "conversation";
        view.enabled = request.enabled == null || Boolean.TRUE.equals(request.enabled);
        view.agentId = request.agentId;
        view.userId = request.userId;
        view.sessionTtlMinutes = request.sessionTtlMinutes != null ? request.sessionTtlMinutes : 60;
        if (request.requireAuth != null) {
            view.requireAuth = request.requireAuth;
        }
        view.config = request.config;
        view.filterConfig = request.filterConfig;
        return view;
    }

    private ChannelResponse channelResponse(ai.core.server.channel.ChannelConfigView view) {
        var response = new ChannelResponse();
        response.channel = toView(view, baseUrl());
        return response;
    }

    private ChannelConfigView toView(ai.core.server.channel.ChannelConfigView channel, String baseUrl) {
        var view = new ChannelConfigView();
        view.channelId = channel.channelId;
        view.channelType = channel.channelType;
        view.mode = channel.mode != null ? channel.mode : "conversation";
        view.enabled = channel.enabled;
        view.requireAuth = channel.requireAuth;
        view.agentId = channel.agentId;
        view.userId = channel.userId;
        view.sessionTtlMinutes = channel.sessionTtlMinutes;
        view.config = channel.config;
        view.filterConfig = channel.filterConfig;
        view.webhookUrl = baseUrl + "/api/channels/" + channel.channelId + "/v1/chat/completions";
        return view;
    }

    private String baseUrl() {
        var request = webContext.request();
        var scheme = request.scheme();
        var hostHeader = request.header("Host").orElse(request.hostname());
        return scheme + "://" + hostHeader;
    }
}
