package ai.core.agent.listener.listeners;

import ai.core.agent.Agent;
import ai.core.agent.listener.MessageUpdatedEventListener;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class DefaultAgentMessageEventListener implements MessageUpdatedEventListener<Agent> {
    private final Logger logger = LoggerFactory.getLogger(DefaultAgentMessageEventListener.class);

    @Override
    public void eventHandler(Agent agent, Message message) {
        logger.info("Agent {}<{}> received new Message: {}", agent.getName(), agent.getId(), message.content);
    }
}
