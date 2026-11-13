package ai.core.server.trace.service;

import ai.core.server.trace.TraceIdentityField;
import ai.core.server.trace.TraceStore;

import java.util.EnumMap;
import java.util.Map;

/**
 * The identity a trace is attributed by (owner, session, agent) is carried by the root span only.
 * Trace creation and span ingestion run on different queues, so a child span can create the trace
 * document first; the root then fills whatever the first writer left empty.
 *
 * @author stephen
 */
final class TraceIdentity {
    static void backfill(TraceStore traceStore, String traceId, Map<String, String> attrs) {
        var values = new EnumMap<TraceIdentityField, String>(TraceIdentityField.class);
        putIfPresent(values, TraceIdentityField.USER_ID, attrs.get("user.id"));
        putIfPresent(values, TraceIdentityField.SESSION_ID, attrs.get("session.id"));
        putIfPresent(values, TraceIdentityField.AGENT_ID, attrs.get("gen_ai.agent.id"));
        putIfPresent(values, TraceIdentityField.AGENT_NAME, attrs.get("gen_ai.agent.name"));
        if (values.isEmpty()) return;
        traceStore.backfillIdentity(traceId, values);
    }

    private static void putIfPresent(Map<TraceIdentityField, String> target, TraceIdentityField field, String value) {
        if (value != null && !value.isBlank()) target.put(field, value);
    }

    private TraceIdentity() {
    }
}
