package ai.core.server.dataset.store;

import core.framework.db.Column;

/**
 * Row size returned by an upsert/patch {@code RETURNING octet_length(data::text)}, used to reject a
 * merged state that grew past the limit and roll the transaction back.
 *
 * @author stephen
 */
public class PgSizeRow {
    @Column(name = "size")
    public Integer size;
}
