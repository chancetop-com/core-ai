package ai.core.server;

import ai.core.api.server.channel.ChannelWebService;
import ai.core.api.server.ocg.OcgConfigWebService;
import ai.core.server.channel.ChannelController;
import ai.core.server.channel.ChannelDispatcher;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.channel.ChannelSyncController;
import ai.core.server.channel.ChannelWebServiceImpl;
import ai.core.server.channel.openclaw.OcgConfigWebServiceImpl;
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

        api().service(ChannelWebService.class, bind(ChannelWebServiceImpl.class));

        // OpenAI-compatible sync endpoint for all channels
        var channelSync = bind(ChannelSyncController.class);
        http().route(HTTPMethod.POST, "/api/channels/:channelId/v1/chat/completions", channelSync);

        api().service(OcgConfigWebService.class, bind(OcgConfigWebServiceImpl.class));
    }
}
