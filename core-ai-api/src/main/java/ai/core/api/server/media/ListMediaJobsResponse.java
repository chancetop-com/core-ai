package ai.core.api.server.media;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListMediaJobsResponse {
    @Property(name = "total")
    public Long total;

    @Property(name = "jobs")
    public List<MediaJobView> jobs;
}
