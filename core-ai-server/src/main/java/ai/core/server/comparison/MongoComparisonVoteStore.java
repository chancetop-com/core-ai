package ai.core.server.comparison;

import ai.core.server.comparison.domain.ComparisonVote;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoComparisonVoteStore implements ComparisonVoteStore {
    public static MongoComparisonVoteStore of(MongoCollection<ComparisonVote> collection) {
        var store = new MongoComparisonVoteStore();
        store.voteCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<ComparisonVote> voteCollection;

    @Override
    public Optional<ComparisonVote> find(String comparisonId, String sessionA, Long turn) {
        return voteCollection.findOne(Filters.and(
            Filters.eq("comparison_id", comparisonId),
            Filters.eq("session_a", sessionA),
            Filters.eq("turn", turn)));
    }

    @Override
    public boolean insertIfAbsent(ComparisonVote vote) {
        try {
            voteCollection.insert(vote);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void replace(ComparisonVote vote) {
        voteCollection.replace(vote);
    }

    @Override
    public List<ComparisonVote> findByComparison(String comparisonId) {
        var query = new Query();
        query.filter = Filters.eq("comparison_id", comparisonId);
        query.sort = Sorts.ascending("created_at");
        return voteCollection.find(query);
    }

    @Override
    public List<ComparisonVote> findBySessionA(String comparisonId, String sessionA) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("comparison_id", comparisonId), Filters.eq("session_a", sessionA));
        return voteCollection.find(query);
    }

    @Override
    public long countBySessionA(String comparisonId, String sessionA) {
        return voteCollection.count(Filters.and(Filters.eq("comparison_id", comparisonId), Filters.eq("session_a", sessionA)));
    }
}
