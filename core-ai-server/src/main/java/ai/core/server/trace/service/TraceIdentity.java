package ai.core.server.trace.service;

import ai.core.server.trace.domain.Trace;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The identity a trace is attributed by (owner, session, agent) is carried by the root span only.
 * Trace creation and span ingestion run on different queues, so a child span can create the trace
 * document first; the root then fills whatever the first writer left empty.
 *
 * @author stephen
 */
final class TraceIdentity {
    static void backfill(MongoCollection<Trace> traceCollection, String traceId, Map<String, String> attrs) {
        var values = new LinkedHashMap<String, String>();
        putIfPresent(values, "user_id", attrs.get("user.id"));
        putIfPresent(values, "session_id", attrs.get("session.id"));
        putIfPresent(values, "agent_id", attrs.get("gen_ai.agent.id"));
        putIfPresent(values, "agent_name", attrs.get("gen_ai.agent.name"));
        if (values.isEmpty()) return;

        var missing = new ArrayList<Bson>();
        var updates = new ArrayList<Bson>();
        for (var entry : values.entrySet()) {
            missing.add(Filters.or(
                Filters.exists(entry.getKey(), false), Filters.eq(entry.getKey(), null), Filters.eq(entry.getKey(), "")));
            updates.add(Updates.set(entry.getKey(), entry.getValue()));
        }
        updates.add(Updates.set("updated_at", ZonedDateTime.now()));
        traceCollection.update(
            Filters.and(Filters.eq("trace_id", traceId), Filters.or(missing)),
            Updates.combine(updates));
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private TraceIdentity() {
    }
}
