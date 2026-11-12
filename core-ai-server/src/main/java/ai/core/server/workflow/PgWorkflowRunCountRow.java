package ai.core.server.workflow;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgWorkflowRunCountRow {
    @Column(name = "total")
    public Long total;
}
