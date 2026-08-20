package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * In-progress HTML report draft of the report-renderer agent: the agent writes the report section
 * by section through the append_report_section tool (single LLM calls cannot emit a full report),
 * and the report stage assembles the sections into the final artifact once the agent run finishes.
 *
 * @author stephen
 */
@Collection(name = "project_report_drafts")
public class ProjectReportDraft {
    @Id
    public String id;          // per-render draft id (passed to the agent via runtime variables)

    @Field(name = "subject_id")
    public String subjectId;

    @Field(name = "sections")
    public List<String> sections;   // HTML fragments in order; the first carries the <style> block

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
