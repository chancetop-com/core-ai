package ai.core.server.project;

import ai.core.agent.ExecutionContext;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Map;

/**
 * Builtin tool entry point of the {@code project-report} tool group, mounted on the builtin
 * report-renderer agent: appends one HTML section to the run's draft (draft_id is auto-injected
 * from the run's runtime variables); the report stage assembles the sections when the agent run
 * finishes.
 *
 * @author stephen
 */
public class ProjectToolDispatcher {
    static final int MAX_SECTION_CHARS = 8000;

    @Inject
    ProjectReportDraftStore draftStore;

    public Map<String, Object> appendReportSection(String sectionHtml, ExecutionContext context) {
        if (sectionHtml == null || sectionHtml.isBlank()) {
            throw new BadRequestException("section_html is required");
        }
        var draftId = runtimeVariable(context, "draft_id");
        if (draftId == null) throw new ForbiddenException("report draft is not available in this run");
        if (draftStore.get(draftId).isEmpty()) {
            throw new ForbiddenException("report draft not found, id=" + draftId);
        }
        var html = sectionHtml.trim();
        if (html.length() > MAX_SECTION_CHARS) html = html.substring(0, MAX_SECTION_CHARS);
        var section = html;
        draftStore.update(draftId, draft -> {
            if (draft.sections == null) draft.sections = new ArrayList<>();
            draft.sections.add(section);
            draft.updatedAt = ZonedDateTime.now();
        });
        return Map.of("appended", Boolean.TRUE);
    }

    private String runtimeVariable(ExecutionContext context, String name) {
        if (context == null || context.getCustomVariables() == null) return null;
        var value = context.getCustomVariables().get(name);
        return value != null ? value.toString() : null;
    }
}
