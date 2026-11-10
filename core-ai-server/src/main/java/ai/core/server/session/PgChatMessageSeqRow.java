package ai.core.server.session;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgChatMessageSeqRow {
    @Column(name = "seq")
    public Long seq;
}
