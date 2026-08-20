package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

/**
 * Cached cost snapshot of a project, stored in its own collection. A separate @Collection entity
 * (rather than an embedded field on {@link Project}) because core-ng only generates codecs for
 * registered entities — partial updates like {@code $set} on a nested class instance would fail
 * with "Can't find a codec". Recomputed by the stats refresh job whenever the project is dirty.
 *
 * @author stephen
 */
@Collection(name = "project_stats")
public class ProjectStatsEntity {
    @Id
    public String id;   // project id

    @Field(name = "stats")
    public ProjectStatsData stats;
}
