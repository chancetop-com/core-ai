package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class MergeSubjectRequest {
    @Property(name = "into_subject_id")
    public String intoSubjectId;
}
