package ai.core.server.schedule;

import ai.core.server.domain.AgentSchedule;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoAgentScheduleStore implements AgentScheduleStore {
    public static MongoAgentScheduleStore of(MongoCollection<AgentSchedule> collection) {
        var store = new MongoAgentScheduleStore();
        store.agentScheduleCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<AgentSchedule> agentScheduleCollection;

    @Override
    public Optional<AgentSchedule> get(String id) {
        return agentScheduleCollection.get(id);
    }

    @Override
    public List<AgentSchedule> list() {
        return agentScheduleCollection.find(Filters.empty());
    }

    @Override
    public List<AgentSchedule> listByAgentId(String agentId) {
        return agentScheduleCollection.find(Filters.eq("agent_id", agentId));
    }

    @Override
    public List<AgentSchedule> listDue(ZonedDateTime now) {
        return agentScheduleCollection.find(Filters.and(
            Filters.eq("enabled", Boolean.TRUE),
            Filters.lte("next_run_at", now)));
    }

    @Override
    public void insert(AgentSchedule schedule) {
        agentScheduleCollection.insert(schedule);
    }

    @Override
    public boolean insertIfAbsent(AgentSchedule schedule) {
        try {
            agentScheduleCollection.insert(schedule);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void replace(AgentSchedule schedule) {
        agentScheduleCollection.replace(schedule);
    }

    @Override
    public void delete(String id) {
        agentScheduleCollection.delete(id);
    }

    @Override
    public boolean claim(String id, ZonedDateTime now, ZonedDateTime nextRunAt) {
        return agentScheduleCollection.update(
            Filters.and(Filters.eq("_id", id), Filters.lte("next_run_at", now)),
            Updates.set("next_run_at", nextRunAt)) == 1;
    }

    @Override
    public boolean rollback(String id, ZonedDateTime claimedNextRunAt, ZonedDateTime originalNextRunAt) {
        return agentScheduleCollection.update(
            Filters.and(Filters.eq("_id", id), Filters.eq("next_run_at", claimedNextRunAt)),
            Updates.set("next_run_at", originalNextRunAt)) == 1;
    }
}
