package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ProjectSubjectStatView {
    @Property(name = "subject_id")
    public String subjectId;

    @Property(name = "tokens")
    public Long tokens;

    @Property(name = "cost_usd")
    public Double costUsd;

    @Property(name = "count")
    public Long count;
}
