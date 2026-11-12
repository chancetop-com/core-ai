package ai.core.server.comparison;

import ai.core.server.comparison.domain.ComparisonVote;
import ai.core.server.comparison.domain.ComparisonVoteChoice;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgComparisonVoteStore implements ComparisonVoteStore {
    private static final String TABLE = "app.comparison_votes";
    private static final String SELECT = "SELECT id, comparison_id, pair_id, session_a, session_b, turn, chosen, "
        + "user_id, created_at, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, comparison_id, pair_id, session_a, session_b, "
        + "turn, chosen, user_id, created_at, data) ";

    private static ComparisonVote toVote(PgComparisonVoteRow row) {
        var vote = JsonUtil.fromJson(ComparisonVote.class, row.data);
        vote.id = row.id;
        vote.comparisonId = row.comparisonId;
        vote.pairId = row.pairId;
        vote.sessionA = row.sessionA;
        vote.sessionB = row.sessionB;
        vote.turn = row.turn;
        vote.chosen = row.chosen != null ? ComparisonVoteChoice.valueOf(row.chosen) : null;
        vote.userId = row.userId;
        vote.createdAt = row.createdAt;
        return vote;
    }

    @Inject
    public Database database;

    @Override
    public Optional<ComparisonVote> find(String comparisonId, String sessionA, Long turn) {
        return database.selectOne(SELECT + " WHERE comparison_id = ? AND session_a = ? AND turn = ?",
                PgComparisonVoteRow.class, comparisonId, sessionA, turn)
            .map(PgComparisonVoteStore::toVote);
    }

    @Override
    public boolean insertIfAbsent(ComparisonVote vote) {
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
            params(vote)) > 0;
    }

    @Override
    public void replace(ComparisonVote vote) {
        database.execute("UPDATE " + TABLE + " SET pair_id = ?, session_b = ?, chosen = ?, comment = ?, "
                + "message_a = ?, message_b = ?, data = ?::jsonb WHERE id = ?",
            vote.pairId, vote.sessionB, vote.chosen != null ? vote.chosen.name() : null, vote.comment,
            vote.messageA, vote.messageB, JsonSanitizer.json(JsonUtil.toJson(vote)), vote.id);
    }

    @Override
    public List<ComparisonVote> findByComparison(String comparisonId) {
        return database.select(SELECT + " WHERE comparison_id = ? ORDER BY created_at ASC, id ASC",
                PgComparisonVoteRow.class, comparisonId)
            .stream().map(PgComparisonVoteStore::toVote).toList();
    }

    @Override
    public List<ComparisonVote> findBySessionA(String comparisonId, String sessionA) {
        return database.select(SELECT + " WHERE comparison_id = ? AND session_a = ? ORDER BY created_at ASC, id ASC",
                PgComparisonVoteRow.class, comparisonId, sessionA)
            .stream().map(PgComparisonVoteStore::toVote).toList();
    }

    @Override
    public long countBySessionA(String comparisonId, String sessionA) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE
                + " WHERE comparison_id = ? AND session_a = ?", PgComparisonVoteCountRow.class, comparisonId, sessionA)
            .map(row -> row.total).orElse(0L);
    }

    private Object[] params(ComparisonVote vote) {
        return new Object[]{
            vote.id,
            vote.comparisonId,
            vote.pairId,
            vote.sessionA,
            vote.sessionB,
            vote.turn,
            vote.chosen != null ? vote.chosen.name() : null,
            vote.userId,
            vote.createdAt,
            JsonSanitizer.json(JsonUtil.toJson(vote))
        };
    }
}

