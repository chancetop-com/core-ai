package ai.core.server.costalert;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public interface CostAlertEventStore {
    /** returns false when the rule already fired for this scope and day */
    boolean insertIfAbsent(CostAlertEvent event);

    List<CostAlertEvent> list(String ruleId, ZonedDateTime from, ZonedDateTime to, int limit);
}
