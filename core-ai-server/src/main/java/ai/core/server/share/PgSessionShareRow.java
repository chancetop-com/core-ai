package ai.core.server.share;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author Xander
 */
public class PgSessionShareRow {
    @Column(name = "data")
    public String data;

    @Column(name = "revoked_at")
    public ZonedDateTime revokedAt;
}
