package ai.core.server.workflow;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgWorkflowMigrationLockRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
