package ai.core.session;

import ai.core.agent.AgentPersistence;

/**
 * Snapshot schema version. A missing version means the file predates the field and is version 1.
 *
 * @author stephen
 */
public final class SnapshotVersion {
    public static final int CURRENT = 1;

    public static void check(AgentPersistence.AgentPersistenceDomain domain) {
        var version = domain.version == null ? 1 : domain.version;
        if (version > CURRENT) {
            throw new UnsupportedSnapshotVersionException(version);
        }
    }

    private SnapshotVersion() {
    }
}
