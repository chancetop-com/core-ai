package ai.core.server;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgStartupRow {
    @Column(name = "ok")
    public Integer ok;
}
