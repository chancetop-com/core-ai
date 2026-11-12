package ai.core.server.costalert;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class MongoCostAlertEventStore implements CostAlertEventStore {
    public static MongoCostAlertEventStore of(MongoCollection<CostAlertEvent> collection) {
        var store = new MongoCostAlertEventStore();
        store.eventCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<CostAlertEvent> eventCollection;

    @Override
    public boolean insertIfAbsent(CostAlertEvent event) {
        try {
            eventCollection.insert(event);
            return true;
        } catch (com.mongodb.DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public List<CostAlertEvent> list(String ruleId, ZonedDateTime from, ZonedDateTime to, int limit) {
        var filters = new java.util.ArrayList<org.bson.conversions.Bson>();
        if (ruleId != null && !ruleId.isBlank()) filters.add(Filters.eq("rule_id", ruleId));
        if (from != null) filters.add(Filters.gte("date", from));
        if (to != null) filters.add(Filters.lt("date", to));
        var query = new Query();
        if (!filters.isEmpty()) query.filter = Filters.and(filters);
        query.sort = Sorts.descending("created_at");
        query.limit = limit;
        return eventCollection.find(query);
    }
}
