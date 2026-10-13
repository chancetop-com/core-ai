package ai.core.server.run;

import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelMessage;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChannelTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers a finished run's output back to the conversation it came from. Extracted from
 * {@link AgentRunner} (file-size budget); a delivery failure is swallowed on purpose — the run itself
 * succeeded, and a broken channel must not turn it into a failed run.
 *
 * @author stephen
 */
public class RunChannelDelivery {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunChannelDelivery.class);

    private final ChannelConfigStore channelConfigStore;
    private final ChannelRegistry channelRegistry;

    public RunChannelDelivery(ChannelConfigStore channelConfigStore, ChannelRegistry channelRegistry) {
        this.channelConfigStore = channelConfigStore;
        this.channelRegistry = channelRegistry;
    }

    public void send(AgentRun runEntity, ChannelTarget channel) {
        var channelId = channel.id();
        var channelRecipientId = channel.recipientId();
        if (channelId == null || channelId.isBlank()) return;
        if (channelRecipientId == null || channelRecipientId.isBlank()) return;

        var channelConfig = channelConfigStore.load(channelId);
        if (channelConfig == null) return;

        try {
            var outbound = channelRegistry.outbound(channelConfig.channelType);
            var message = ChannelMessage.text(runEntity.output);
            outbound.sendMessage(message, channelId, channelRecipientId, channelRecipientId, null, channelConfig.config);
        } catch (Exception e) {
            LOGGER.debug("channel delivery failed for run {}, ignored", runEntity.id, e);
        }
    }
}
