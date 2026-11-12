package ai.core.server.replay;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgReplayExperimentCountRow {
    @Column(name = "total")
    public Long total;
}
