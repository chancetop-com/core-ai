package ai.core.server.schedule;

import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskStore;

import java.util.List;

/**
 * @author stephen
 */
public interface SessionScheduleStore extends ScheduledTaskStore {
    List<ScheduledTask> listPage(int offset, int limit);

    long count();

    ScheduledTask updateEnabled(String id, boolean enabled);

    boolean insertIfAbsent(ScheduledTask task);
}

