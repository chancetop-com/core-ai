package ai.core.server.messaging;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgPodAddressRow {
    @Column(name = "address")
    public String address;
}
