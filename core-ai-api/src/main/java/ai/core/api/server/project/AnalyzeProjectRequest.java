package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class AnalyzeProjectRequest {
    // optional: restrict the run to one subject (subject-page "Analyze now")
    @Property(name = "subject_id")
    public String subjectId;
}
