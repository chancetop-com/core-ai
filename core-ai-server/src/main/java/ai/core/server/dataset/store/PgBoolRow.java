package ai.core.server.dataset.store;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgBoolRow {
    @Column(name = "acquired")
    public Boolean acquired;
}
