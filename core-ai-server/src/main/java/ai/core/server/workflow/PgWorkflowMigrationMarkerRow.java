package ai.core.server.workflow;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgWorkflowMigrationMarkerRow {
    @Column(name = "id")
    public String id;
}
