package ai.core.server;

import ai.core.server.channel.ChannelAdminController;
import ai.core.server.channel.ChannelController;
import ai.core.server.channel.ChannelDispatcher;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.channel.ChannelSyncController;
import ai.core.server.channel.openclaw.OcgConfigController;
import ai.core.server.channel.slack.SlackInboundAdapter;
import ai.core.server.channel.slack.SlackOutboundAdapter;
import ai.core.server.channel.telegram.TelegramInboundAdapter;
import ai.core.server.channel.telegram.TelegramOutboundAdapter;
import ai.core.server.channel.weclaw.WeClawInboundAdapter;
import ai.core.server.channel.weclaw.WeClawOutboundAdapter;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ChannelModule extends Module {

    @Override
    protected void initialize() {
        bindChannelRegistry();
        bindChannels();
    }


    private void bindChannelRegistry() {
        var registry = bean(ChannelRegistry.class);

        // Register channel adapters — each pair handles inbound verification/parsing
        // and outbound message delivery for a specific platform.
        var slackInbound = new SlackInboundAdapter();
        var slackOutbound = new SlackOutboundAdapter();
        registry.register(slackInbound, slackOutbound);

        var telegramInbound = new TelegramInboundAdapter();
        var telegramOutbound = new TelegramOutboundAdapter();
        registry.register(telegramInbound, telegramOutbound);

        var weclawInbound = new WeClawInboundAdapter();
        var weclawOutbound = new WeClawOutboundAdapter();
        registry.register(weclawInbound, weclawOutbound);
    }

    private void bindChannels() {
        bind(ChannelDispatcher.class);

        // Unified webhook endpoint for all channels
        var channelController = bind(ChannelController.class);
        http().route(HTTPMethod.POST, "/api/channels/:channelId", channelController);
        http().route(HTTPMethod.GET, "/api/channels/:channelId", channelController);

        // Channel admin CRUD endpoints
        var channelAdmin = bind(ChannelAdminController.class);

        // OpenAI-compatible sync endpoint for all channels
        var channelSync = bind(ChannelSyncController.class);
        http().route(HTTPMethod.POST, "/api/channels/:channelId/v1/chat/completions", channelSync);

        var ocgConfigController = bind(OcgConfigController.class);
        http().route(HTTPMethod.GET, "/api/admin/ocg-configs", ocgConfigController::list);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs", ocgConfigController::create);
        http().route(HTTPMethod.GET, "/api/admin/ocg-configs/:id", ocgConfigController::get);
        http().route(HTTPMethod.PUT, "/api/admin/ocg-configs/:id", ocgConfigController::update);
        http().route(HTTPMethod.DELETE, "/api/admin/ocg-configs/:id", ocgConfigController::delete);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs/:id/start", ocgConfigController::start);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs/:id/stop", ocgConfigController::stop);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs/:id/restart", ocgConfigController::restart);
        http().route(HTTPMethod.POST, "/api/admin/ocg-configs/:id/command", ocgConfigController::command);
        http().route(HTTPMethod.GET, "/api/admin/ocg-configs/:id/logs", ocgConfigController::logs);
        http().route(HTTPMethod.GET, "/api/admin/ocg-configs/:id/status", ocgConfigController::status);

        http().route(HTTPMethod.GET, "/api/admin/channels", channelAdmin::list);
        http().route(HTTPMethod.POST, "/api/admin/channels", channelAdmin::create);
        http().route(HTTPMethod.GET, "/api/admin/channels/:channelId", channelAdmin::get);
        http().route(HTTPMethod.PUT, "/api/admin/channels/:channelId", channelAdmin::update);
        http().route(HTTPMethod.DELETE, "/api/admin/channels/:channelId", channelAdmin::delete);
        http().route(HTTPMethod.GET, "/api/admin/channel-types", channelAdmin::types);
    }
}
