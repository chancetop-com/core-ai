package ai.core.cli;

import ai.core.cli.appserver.RpcException;
import ai.core.cli.appserver.SessionIds;
import ai.core.session.SessionFileNames;
import ai.core.session.SessionLoad;
import ai.core.session.SessionLoads;
import ai.core.session.SessionPersistence;

/**
 * Resolves an explicit {@code --resume <session-id>} request before the agent is built. The id must be
 * a valid session file key and the snapshot must be readable, so a typo can never start a turn and save
 * a new session under the requested id.
 *
 * @author stephen
 */
public final class SessionResume {
    public static String requireReadable(SessionPersistence persistence, String requestedId) {
        try {
            SessionIds.check(requestedId);
        } catch (RpcException e) {
            throw new SessionResumeException("Invalid session id '" + requestedId
                    + "': use letters, digits, '.', '_', '~' and '-' only", e);
        }
        try {
            SessionFileNames.check(requestedId);
        } catch (IllegalArgumentException e) {
            throw new SessionResumeException("Invalid session id '" + requestedId + "': " + e.getMessage(), e);
        }
        return switch (SessionLoads.load(persistence, requestedId)) {
            case SessionLoad.Ok ignored -> requestedId;
            case SessionLoad.Missing ignored -> throw new SessionResumeException(
                    "Session not found in this workspace: " + requestedId);
            case SessionLoad.Corrupt corrupt -> throw new SessionResumeException(
                    "Session file is corrupted: " + requestedId + " (" + corrupt.reason() + ")");
            case SessionLoad.NewerVersion newer -> throw new SessionResumeException(
                    "Session was written by a newer core-ai version: " + requestedId
                            + " (version " + newer.version() + "). Upgrade core-ai first.");
            case SessionLoad.IoError error -> throw new SessionResumeException(
                    "Failed to read session " + requestedId + ": " + error.cause().getMessage());
        };
    }

    private SessionResume() {
    }
}
