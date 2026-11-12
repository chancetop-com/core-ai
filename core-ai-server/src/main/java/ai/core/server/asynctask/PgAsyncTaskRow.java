package ai.core.server.asynctask;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgAsyncTaskRow {
    @Column(name = "id")
    public String id;

    @Column(name = "data")
    public String data;
}
