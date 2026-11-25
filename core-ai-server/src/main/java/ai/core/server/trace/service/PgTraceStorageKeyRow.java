package ai.core.server.trace.service;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgTraceStorageKeyRow {
    @Column(name = "storage_key")
    public String storageKey;
}
