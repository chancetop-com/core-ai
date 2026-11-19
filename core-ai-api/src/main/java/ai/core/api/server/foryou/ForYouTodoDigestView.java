package ai.core.api.server.foryou;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ForYouTodoDigestView {
    @Property(name = "date")
    public String date;

    @Property(name = "status")
    public String status;

    @Property(name = "model")
    public String model;

    @Property(name = "prompt_version")
    public String promptVersion;

    @Property(name = "session_count")
    public Integer sessionCount;

    @Property(name = "message_count")
    public Integer messageCount;

    @Property(name = "todo_count")
    public Integer todoCount;

    @Property(name = "skipped_count")
    public Integer skippedCount;

    @Property(name = "error")
    public String error;

    @Property(name = "started_at")
    public String startedAt;

    @Property(name = "completed_at")
    public String completedAt;
}
