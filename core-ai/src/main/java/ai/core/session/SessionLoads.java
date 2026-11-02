package ai.core.session;

import ai.core.agent.AgentPersistence;
import core.framework.json.JSON;

import java.io.UncheckedIOException;

/**
 * Single entry point for session snapshot reads: it classifies every outcome so the listing, the
 * resume path and the CLI pickers agree on what a file means.
 *
 * @author stephen
 */
public final class SessionLoads {
    public static SessionLoad load(SessionPersistence persistence, String id) {
        try {
            if (!persistence.exists(id)) return new SessionLoad.Missing();
        } catch (IllegalArgumentException e) {
            return new SessionLoad.Corrupt(e.getMessage());
        }
        try {
            var content = persistence.load(id).orElse(null);
            if (content == null) return new SessionLoad.Missing();
            if (content.isBlank()) return new SessionLoad.Corrupt("empty file");
            var domain = JSON.fromJSON(AgentPersistence.AgentPersistenceDomain.class, content);
            SnapshotVersion.check(domain);
            return new SessionLoad.Ok(domain);
        } catch (UnsupportedSnapshotVersionException e) {
            return new SessionLoad.NewerVersion(e.version());
        } catch (UncheckedIOException e) {
            if (isJsonFailure(e)) return new SessionLoad.Corrupt(e.getMessage());
            return new SessionLoad.IoError(e.getCause());
        } catch (RuntimeException e) {
            return new SessionLoad.Corrupt(e.getMessage());
        }
    }

    private static boolean isJsonFailure(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            var name = cause.getClass().getName();
            if (name.startsWith("com.fasterxml.jackson") || name.startsWith("tools.jackson")) return true;
        }
        return false;
    }

    public static SessionTitle title(SessionPersistence persistence, String id) {
        var load = load(persistence, id);
        return switch (load) {
            case SessionLoad.Ok ok -> new SessionTitle(AgentPersistence.firstUserMessage(ok.domain()), SessionLoadState.OK);
            case SessionLoad.Missing ignored -> new SessionTitle(null, SessionLoadState.MISSING);
            case SessionLoad.Corrupt ignored -> new SessionTitle(null, SessionLoadState.CORRUPT);
            case SessionLoad.NewerVersion ignored -> new SessionTitle(null, SessionLoadState.NEWER_VERSION);
            case SessionLoad.IoError ignored -> new SessionTitle(null, SessionLoadState.IO_ERROR);
        };
    }

    private SessionLoads() {
    }
}
