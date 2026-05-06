package ai.core.server.trigger.action;

import ai.core.server.trigger.domain.Trigger;

/**
 * @author stephen
 */
public interface TriggerAction {
    String type();

    TriggerActionResult execute(Trigger trigger, String payload);
}
