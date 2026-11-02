package ai.core.cli.appserver;

import ai.core.session.SessionLoadState;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence;
import ai.core.session.SessionTitle;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves the display title for a session from its rename sidecar and its snapshot. */
final class SessionTitleResolver {
    private static final int MAX_TITLE_CHARS = 160;

    private static String truncate(String text, int max) {
        var oneLine = text.replaceAll("[\\r\\n]+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }

    private static String stateName(SessionLoadState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    private final SessionTitleStore titles;
    private final SessionManager sessionManager;
    private final Map<String, EngineSession> sessions;
    private final Map<String, CachedTitle> titleCache = new ConcurrentHashMap<>();

    SessionTitleResolver(SessionTitleStore titles, SessionManager sessionManager, Map<String, EngineSession> sessions) {
        this.titles = titles;
        this.sessionManager = sessionManager;
        this.sessions = sessions;
    }

    ObjectNode summary(SessionPersistence.SessionInfo info) {
        var node = Params.object();
        var title = resolvedTitle(info);
        node.put("sessionId", info.id());
        node.put("title", title.text());
        node.put("state", stateName(title.state()));
        node.put("updatedAt", info.lastModified().toString());
        var live = sessions.get(info.id());
        node.put("status", live != null && live.running() ? "running" : "idle");
        return node;
    }

    /** Title for the schedules page: renamed sidecar first, then the first user message. */
    String sessionTitle(String sessionId) {
        var renamed = titles.title(sessionId);
        if (renamed.isPresent()) return renamed.get();
        var parsed = sessionManager.title(sessionId);
        if (parsed.text() != null && !parsed.text().isBlank()) {
            return truncate(parsed.text().strip(), MAX_TITLE_CHARS);
        }
        return parsed.state() == SessionLoadState.OK ? null : sessionId;
    }

    void invalidate(String sessionId) {
        titleCache.remove(sessionId);
    }

    private SessionTitle resolvedTitle(SessionPersistence.SessionInfo info) {
        var cached = titleCache.get(info.id());
        if (cached != null && cached.updatedAt().equals(info.lastModified())) {
            return new SessionTitle(cached.text(), cached.state());
        }
        var renamed = titles.title(info.id());
        var parsed = sessionManager.title(info.id());
        var text = renamed.orElseGet(() -> parsed.text() == null || parsed.text().isBlank()
                ? info.id()
                : truncate(parsed.text().strip(), MAX_TITLE_CHARS));
        titleCache.put(info.id(), new CachedTitle(info.lastModified(), text, parsed.state()));
        return new SessionTitle(text, parsed.state());
    }

    private record CachedTitle(Instant updatedAt, String text, SessionLoadState state) {
    }
}
