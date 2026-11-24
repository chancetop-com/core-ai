package ai.core.server.agenthealth;

import java.time.ZonedDateTime;

/**
 * Singleton reconciler state. backfillDone stays false until one full sweep has recorded existing
 * issues silently, so a first deployment cannot notify every historical broken reference.
 *
 * @author core-ai
 */
public class AgentHealthState {
    public String id;
    public Boolean backfillDone;
    public String cursor;
    public ZonedDateTime lastRunAt;
    public ZonedDateTime lastFullSweepAt;
}
