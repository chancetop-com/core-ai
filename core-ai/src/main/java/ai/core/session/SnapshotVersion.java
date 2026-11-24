package ai.core.session;

import ai.core.agent.AgentPersistence;

/**
 * Snapshot schema version. A missing version means the file predates the field and is version 1.
 *
 * @author stephen
 */
public final class SnapshotVersion {
    /**
     * 2: conversation state may carry {@code Content.IMAGE_REF} instead of inline image bytes. A version
     * 1 reader must reject it (no silent downgrade); a version 2 reader still accepts version 1.
     */
    public static final int CURRENT = 2;

    public static void check(AgentPersistence.AgentPersistenceDomain domain) {
        var version = domain.version == null ? 1 : domain.version;
        if (version > CURRENT) {
            throw new UnsupportedSnapshotVersionException(version);
        }
    }

    private SnapshotVersion() {
    }
}
