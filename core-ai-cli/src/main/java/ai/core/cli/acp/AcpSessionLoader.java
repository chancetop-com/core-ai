package ai.core.cli.acp;

import ai.core.agent.Agent;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionLoad;
import ai.core.session.SessionLoads;

/** Restores an ACP session from its snapshot, reporting corrupt and newer files as startup errors. */
final class AcpSessionLoader {
    static void restore(Agent agent, FileSessionPersistence persistence, String sessionId) {
        switch (SessionLoads.load(persistence, sessionId)) {
            case SessionLoad.Ok ok -> agent.restore(sessionId, ok.domain());
            case SessionLoad.Missing ignored -> {
            }
            case SessionLoad.Corrupt corrupt -> throw new IllegalStateException(
                    "session file is corrupted: " + sessionId + " (" + corrupt.reason() + ")");
            case SessionLoad.NewerVersion newer -> throw new IllegalStateException(
                    "session was written by a newer core-ai version: " + sessionId + " (version " + newer.version() + ")");
            case SessionLoad.IoError error -> throw new IllegalStateException(
                    "failed to read session: " + sessionId, error.cause());
        }
    }

    private AcpSessionLoader() {
    }
}
