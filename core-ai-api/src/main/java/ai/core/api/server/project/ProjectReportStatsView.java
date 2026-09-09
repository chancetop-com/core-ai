package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Report counts of a project: the unassigned inbox size plus, per subject, how many reports are filed and when
 * the newest one was produced. Cheap enough to render on the project page cards without listing reports.
 *
 * @author stephen
 */
public class ProjectReportStatsView {
    // unfiled reports among the recent member material (same bound as the inbox list)
    @Property(name = "unassigned")
    public Long unassigned;

    @Property(name = "subjects")
    public List<SubjectStat> subjects;

    public static class SubjectStat {
        @Property(name = "subject_id")
        public String subjectId;

        @Property(name = "count")
        public Long count;

        @Property(name = "latest_at")
        public ZonedDateTime latestAt;
    }
}
