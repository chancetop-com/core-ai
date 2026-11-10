package ai.core.server.file;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgFileRecordUserRow {
    @Column(name = "user_id")
    public String userId;
}
