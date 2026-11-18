package ai.core.server.plugin;

import core.framework.db.Column;
import core.framework.db.Table;

/**
 * @author stephen
 */
@Table(name = "app.plugins")
public class PgPluginCountRow {
    @Column(name = "total")
    public Long total;
}
