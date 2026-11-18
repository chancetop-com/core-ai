package ai.core.cli.appserver;

import ai.core.session.SessionPersistence;
import ai.core.session.transcript.TranscriptReader;
import ai.core.session.transcript.TranscriptStore;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Workspace-wide token/cost totals for plugin pages. It reads the persisted transcripts, so the
 * result survives engine restarts and does not require resuming a session.
 *
 * @author stephen
 */
public final class UsageApi {
    private static final int MAX_SESSIONS = 200;

    static ObjectNode summarize(Path sessionsDir, List<SessionPersistence.SessionInfo> sessions,
                                Instant cutoff, int limit) {
        var totals = new Totals();
        var rows = Params.object().putArray("sessions");
        int counted = 0;
        for (var session : sessions) {
            if (cutoff != null && session.lastModified().isBefore(cutoff)) break;
            if (counted >= limit) break;
            counted++;
            var usage = usageOf(sessionsDir, session.id());
            if (usage == null || usage.completedTurns() == 0) continue;
            totals.add(usage);
            rows.add(row(session, usage));
        }
        return result(totals, rows);
    }

    private static TranscriptReader.Usage usageOf(Path sessionsDir, String sessionId) {
        TranscriptStore store = null;
        try {
            store = TranscriptStore.open(sessionsDir, sessionId);
            return new TranscriptReader(store).usage();
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (store != null) store.close();
        }
    }

    private static ObjectNode row(SessionPersistence.SessionInfo session, TranscriptReader.Usage usage) {
        var row = Params.object();
        row.put("sessionId", session.id());
        row.put("updatedAt", session.lastModified().toString());
        row.put("inputTokens", usage.inputTokens());
        row.put("outputTokens", usage.outputTokens());
        row.put("cachedTokens", usage.cachedTokens());
        row.put("costUsd", usage.costUsd());
        row.put("turns", usage.turns());
        row.put("completedTurns", usage.completedTurns());
        return row;
    }

    private static ObjectNode result(Totals totals, ArrayNode rows) {
        var node = Params.object();
        node.set("totals", totals.node(rows.size()));
        node.set("sessions", rows);
        return node;
    }

    private final Path sessionsDir;
    private final SessionPersistence persistence;

    public UsageApi(Path sessionsDir, SessionPersistence persistence) {
        this.sessionsDir = sessionsDir;
        this.persistence = persistence;
    }

    public ObjectNode summary(ObjectNode params) {
        var range = Params.optionalText(params, "range");
        if (range == null) range = "all";
        var node = summarize(sessionsDir, persistence.listSessions(), cutoff(range), MAX_SESSIONS);
        node.put("range", range);
        return node;
    }

    private Instant cutoff(String range) {
        var now = Instant.now();
        return switch (range) {
            case "all" -> null;
            case "7d" -> now.minus(Duration.ofDays(7));
            case "30d" -> now.minus(Duration.ofDays(30));
            default -> throw RpcException.invalidParams("range must be all, 7d or 30d");
        };
    }

    private static final class Totals {
        private long input;
        private long output;
        private long cached;
        private double cost;
        private int turns;
        private int completedTurns;

        void add(TranscriptReader.Usage usage) {
            input += usage.inputTokens();
            output += usage.outputTokens();
            cached += usage.cachedTokens();
            cost += usage.costUsd();
            turns += usage.turns();
            completedTurns += usage.completedTurns();
        }

        ObjectNode node(int sessions) {
            var node = Params.object();
            node.put("sessions", sessions);
            node.put("inputTokens", input);
            node.put("outputTokens", output);
            node.put("cachedTokens", cached);
            node.put("costUsd", cost);
            node.put("turns", turns);
            node.put("completedTurns", completedTurns);
            return node;
        }
    }
}
