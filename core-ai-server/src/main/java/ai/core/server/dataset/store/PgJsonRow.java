package ai.core.server.dataset.store;

import core.framework.db.Column;

/**
 * Single-value row for aggregate SQL: dynamic shapes are serialized to JSON in the database and parsed
 * in Java, because a core-ng view cannot declare a variable number of columns.
 *
 * @author stephen
 */
public class PgJsonRow {
    @Column(name = "payload")
    public String payload;
}
