package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class MoveProjectReportRequest {
    // target subject; null/blank = back to the project's unassigned bucket
    @Property(name = "subject_id")
    public String subjectId;
}
