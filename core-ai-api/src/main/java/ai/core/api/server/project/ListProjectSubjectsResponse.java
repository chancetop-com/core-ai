package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListProjectSubjectsResponse {
    @Property(name = "subjects")
    public List<ProjectSubjectView> subjects;

    @Property(name = "total")
    public Long total;
}
