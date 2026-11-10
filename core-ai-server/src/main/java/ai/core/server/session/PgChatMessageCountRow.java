package ai.core.server.session;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgChatMessageCountRow {
    @Column(name = "total")
    public Long total;
}
