package ai.core.server.sandbox.snapshot;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSandboxSnapshotMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
