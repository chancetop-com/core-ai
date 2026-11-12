package ai.core.server.schedule;

import ai.core.server.domain.AgentSchedule;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface AgentScheduleStore {
    Optional<AgentSchedule> get(String id);

    List<AgentSchedule> list();

    List<AgentSchedule> listByAgentId(String agentId);

    List<AgentSchedule> listDue(ZonedDateTime now);

    void insert(AgentSchedule schedule);

    boolean insertIfAbsent(AgentSchedule schedule);

    void replace(AgentSchedule schedule);

    void delete(String id);

    boolean claim(String id, ZonedDateTime now, ZonedDateTime nextRunAt);

    boolean rollback(String id, ZonedDateTime claimedNextRunAt, ZonedDateTime originalNextRunAt);
}
