package ai.core.server.session;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSessionActivityRow {
    @Column(name = "last_activity")
    public Long lastActivity;
}
