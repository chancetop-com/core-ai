package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * One narrative/timeline entry (product decision D3: titles/raw tool input, no LLM summaries).
 *
 * @author stephen
 */
public class TimelineEntryView {
    @Property(name = "type")
    public String type;   // session | report | status | kpi | note | action_item

    @Property(name = "title")
    public String title;

    @Property(name = "detail")
    public String detail;

    @Property(name = "subject_id")
    public String subjectId;

    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "trace_id")
    public String traceId;

    @Property(name = "at")
    public ZonedDateTime at;
}
