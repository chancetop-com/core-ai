package ai.core.server.channel;

import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * @author stephen
 */
public class ChannelController implements Controller {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelController.class);

    @Inject
    ChannelRegistry channelRegistry;

    @Inject
    ChannelDispatcher channelDispatcher;

    @Inject
    ChannelConfigStore configStore;

    @Override
    public Response execute(Request request) {
        var channelId = request.pathParam("channelId");
        if (channelId == null || channelId.isBlank()) {
            throw new BadRequestException("channelId is required");
        }

        var channel = configStore.load(channelId);
        if (channel == null) {
            throw new NotFoundException("channel not found: " + channelId);
        }
        if (Boolean.FALSE.equals(channel.enabled)) {
            throw new ForbiddenException("channel is disabled: " + channelId);
        }

        var inbound = channelRegistry.inbound(channel.channelType);

        // Handle platform URL verification challenge (Slack url_verification, etc.)
        var challenge = inbound.handleChallenge(request, channel.config);
        if (challenge.isPresent()) {
            return challenge.get();
        }

        // Verify request authenticity
        try {
            inbound.verify(request, channel.config);
        } catch (ForbiddenException e) {
            LOGGER.warn("channel verification failed, channelId={}, type={}", channelId, channel.channelType);
            throw e;
        }

        // Parse inbound event
        var event = inbound.parseEvent(request, channel.config);
        if (event == null) {
            LOGGER.debug("channel event ignored by adapter, channelId={}, type={}", channelId, channel.channelType);
            return Response.text("ignored");
        }
        event.channelType = channel.channelType;

        // Apply event filter if configured
        if (channel.filterConfig != null && !channel.filterConfig.isEmpty()) {
            var filter = new ai.core.server.trigger.filter.EventFilter(channel.filterConfig);
            var body = bodyAsString(request);
            if (!filter.matches(body)) {
                LOGGER.info("channel event filtered, channelId={}, type={}", channelId, channel.channelType);
                return Response.text("filtered");
            }
        }

        // Dispatch to agent session
        channelDispatcher.dispatch(channel, event);

        return Response.text("ok");
    }

    private String bodyAsString(Request request) {
        var body = request.body();
        if (body.isEmpty()) return "";
        return new String(body.get(), StandardCharsets.UTF_8);
    }
}
