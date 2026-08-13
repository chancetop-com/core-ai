package ai.core.api.server.foryou;

import ai.core.api.server.session.ChatSessionSummaryView;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ForYouDashboardView {
    @Property(name = "report_count")
    public Long reportCount;

    @Property(name = "todo_count")
    public Long todoCount;

    @Property(name = "active_todo_count")
    public Long activeTodoCount;

    @Property(name = "file_count")
    public Long fileCount;

    @Property(name = "recent_sessions")
    public List<ChatSessionSummaryView> recentSessions;

    @Property(name = "recent_reports")
    public List<ForYouReportView> recentReports;

    @Property(name = "active_todos")
    public List<ForYouTodoView> activeTodos;

    @Property(name = "recent_files")
    public List<ForYouFileView> recentFiles;
}
