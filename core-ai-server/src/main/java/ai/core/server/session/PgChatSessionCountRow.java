package ai.core.server.session;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgChatSessionCountRow {
    @Column(name = "total")
    public Long total;
}
