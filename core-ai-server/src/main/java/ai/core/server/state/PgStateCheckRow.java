package ai.core.server.state;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgStateCheckRow {
    @Column(name = "ok")
    public Integer ok;
}
