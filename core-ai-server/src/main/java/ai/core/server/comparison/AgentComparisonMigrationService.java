package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.ComparisonVote;
import ai.core.server.util.MigrationErrors;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
public class AgentComparisonMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentComparisonMigrationService.class);
    private static final String COMPARISON_MARKER = "agent_comparisons_v1";
    private static final String VOTE_MARKER = "comparison_votes_v1";
    private static final String LOCK_KEY = "agent_comparison_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<AgentComparison> comparisonCollection;
    @Inject
    MongoCollection<ComparisonVote> voteCollection;
    @Inject
    AgentComparisonStore comparisonStore;
    @Inject
    ComparisonVoteStore voteStore;
    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgComparisonMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("agent comparison migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone(COMPARISON_MARKER) && migrationDone(VOTE_MARKER)) {
                transaction.commit();
                return;
            }
            long comparisons = migrationDone(COMPARISON_MARKER) ? 0 : copyComparisons();
            long votes = migrationDone(VOTE_MARKER) ? 0 : copyVotes();
            insertMarker(COMPARISON_MARKER);
            insertMarker(VOTE_MARKER);
            transaction.commit();
            LOGGER.info("agent comparison migration finished, comparisons={}, votes={}", comparisons, votes);
        }
    }

    private boolean migrationDone(String marker) {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgComparisonMigrationMarkerRow.class, marker).isPresent();
    }

    private void insertMarker(String marker) {
        if (!migrationDone(marker)) {
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", marker);
        }
    }

    private long copyComparisons() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<AgentComparison> page = comparisonCollection.find(query);
            if (page.isEmpty()) return count;
            for (var comparison : page) {
                try {
                    comparisonStore.insert(comparison);
                } catch (RuntimeException e) {
                    if (!MigrationErrors.isDuplicateKey(e)) throw e;
                    LOGGER.debug("comparison already migrated, id={}", comparison.id);
                }
                lastId = comparison.id;
                count++;
            }
        }
    }

    private long copyVotes() {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<ComparisonVote> page = voteCollection.find(query);
            if (page.isEmpty()) return count;
            for (var vote : page) {
                voteStore.insertIfAbsent(vote);
                lastId = vote.id;
                count++;
            }
        }
    }
}
