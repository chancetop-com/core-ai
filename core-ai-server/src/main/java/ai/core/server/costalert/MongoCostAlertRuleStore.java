package ai.core.server.costalert;

import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoCostAlertRuleStore implements CostAlertRuleStore {
    public static MongoCostAlertRuleStore of(MongoCollection<CostAlertRule> collection) {
        var store = new MongoCostAlertRuleStore();
        store.ruleCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<CostAlertRule> ruleCollection;

    @Override
    public Optional<CostAlertRule> get(String id) {
        return ruleCollection.get(id);
    }

    @Override
    public List<CostAlertRule> list() {
        return ruleCollection.find(new Query());
    }

    @Override
    public void insert(CostAlertRule rule) {
        ruleCollection.insert(rule);
    }

    @Override
    public boolean insertIfAbsent(CostAlertRule rule) {
        try {
            ruleCollection.insert(rule);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void replace(CostAlertRule rule) {
        ruleCollection.replace(rule);
    }

    @Override
    public void delete(String id) {
        ruleCollection.delete(id);
    }
}
