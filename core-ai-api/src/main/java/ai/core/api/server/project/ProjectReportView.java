package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ProjectReportView {
    @Property(name = "file_id")
    public String fileId;

    @Property(name = "file_name")
    public String fileName;

    @Property(name = "content_type")
    public String contentType;

    @Property(name = "size")
    public Long size;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "subject_id")
    public String subjectId;

    @Property(name = "agent_id")
    public String agentId;   // the member (agent) whose session/run produced this report; null for manual mounts

    @Property(name = "agent_name")
    public String agentName;
}
