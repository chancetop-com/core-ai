package ai.core.server.plugin;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgPluginCountRow {
    @Column(name = "total")
    public Long total;
}
