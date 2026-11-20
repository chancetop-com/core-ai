package ai.core.cli.appserver;

import ai.core.session.SessionPersistence;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Session-list pagination: running turns lead the list by turn start, saved sessions keep their order.
 *
 * @author stephen
 */
final class SessionListing {
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    static ObjectNode list(EngineBootstrap bootstrap, SessionTitleResolver titleResolver,
                           Map<String, EngineSession> sessions, ObjectNode params) {
        int offset = Math.max(0, Params.intParam(params, "cursor", 0));
        int limit = Math.max(1, Math.min(Params.intParam(params, "limit", DEFAULT_LIST_LIMIT), MAX_LIST_LIMIT));
        var all = runningTurnsFirst(bootstrap.sessionManager.listSessions(), runningSince(sessions));
        int end = Math.min(offset + limit, all.size());
        var page = offset < end ? all.subList(offset, end) : List.<SessionPersistence.SessionInfo>of();
        var node = Params.object();
        var array = node.putArray("sessions");
        for (var info : page) {
            array.add(titleResolver.summary(info));
        }
        if (end < all.size()) {
            node.put("nextCursor", String.valueOf(end));
        }
        return node;
    }

    /**
     * A running turn is a session's newest activity, but its snapshot only takes a new date when the
     * turn saves: running sessions lead the list by turn start, the rest keep their saved order.
     */
    static List<SessionPersistence.SessionInfo> runningTurnsFirst(List<SessionPersistence.SessionInfo> infos,
                                                                  Map<String, Long> runningSince) {
        if (runningSince.isEmpty()) return infos;
        var ordered = new ArrayList<>(infos);
        ordered.sort(Comparator.comparingLong((SessionPersistence.SessionInfo info) ->
                runningSince.getOrDefault(info.id(), Long.MIN_VALUE)).reversed());
        return ordered;
    }

    private static Map<String, Long> runningSince(Map<String, EngineSession> sessions) {
        var running = new HashMap<String, Long>();
        for (var session : sessions.values()) {
            if (session.running()) running.put(session.id(), session.turnStartedAt());
        }
        return running;
    }

    private SessionListing() {
    }
}
