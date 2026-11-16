package ai.core.server.messaging;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgLeaseOwnerRow {
    @Column(name = "owner_pod")
    public String ownerPod;
}
