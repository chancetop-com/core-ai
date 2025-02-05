package ai.core.agent.listener.listeners;

import ai.core.agent.Agent;
import ai.core.agent.listener.ChainNodeStatusChangedEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class DefaultAgentRunningEventListener implements ChainNodeStatusChangedEventListener<Agent> {
    private final Logger logger = LoggerFactory.getLogger(DefaultAgentRunningEventListener.class);

    @Override
    public void eventHandler(Agent node) {
        logger.info("Node {}<{}>'s raw query: {}", node.getName(), node.getId(), node.getOutput());
    }
}