package ai.core.server.trace.domain;

import core.framework.mongo.Field;

/**
 * Aggregation view for trace facet counts: $group emits the facet value as _id.
 *
 * @author Xander
 */
public class TraceFacetRow {
    @Field(name = "_id")
    public String value;

    @Field(name = "count")
    public Long count;
}
