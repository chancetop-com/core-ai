package ai.core.server.memory.experiment;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgMemoryExperimentCountRow {
    @Column(name = "count")
    public Long count;
}
