package ai.core.server.project;

import ai.core.agent.ExecutionContext;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.function.Function;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the {@code project} builtin tool group (get_project_info / attribute_subjects /
 * analyze_subject) that the builtin project-agent mounts. project_id is declared on each tool but
 * is normally auto-injected from the run's runtime variables; subject-scoped material queries
 * join the attribution table.
 *
 * @author stephen
 */
public class ProjectTools {
    public static final String TOOL_SET_NAME = "builtin:project";
    public static final String REPORT_TOOL_SET_NAME = "builtin:project-report";

    @Inject
    ProjectToolDispatcher dispatcher;
    @Inject
    ToolRegistryService toolRegistryService;

    public void initialize() {
        var tools = new ArrayList<ToolCall>();
        tools.add(build("get_project_info",
            "Load one project's context: playbook, goal, report sources, members, subjects (with per-subject attribution counts and analysis cursors) and the project analysis cursor.",
            List.of(required("project_id", "Project ID")),
            method("getProjectInfo", String.class)));
        toolRegistryService.registerBuiltinToolGroup(TOOL_SET_NAME, "Project",
            "Project analysis tools for the builtin project agent: project context lookup (the attribution and subject-analysis writers are mounted as LLM_CALL tools)",
            tools);
        var reportTools = new ArrayList<ToolCall>();
        reportTools.add(build("append_report_section",
            "Append ONE section of the campaign report HTML. The report is too long for a single reply, so write it section by section: call this once per section with a complete, valid HTML fragment (the first call must carry the <style> block and the subject headline). Do NOT put HTML in your reply text.",
            List.of(required("section_html", "One complete HTML fragment (≤4000 chars; the first call must include the <style> block)")),
            method("appendReportSection", String.class)));
        toolRegistryService.registerBuiltinToolGroup(REPORT_TOOL_SET_NAME, "Project report",
            "Report writing tool for the builtin project report renderer: sections are assembled into the subject's report automatically when the render finishes",
            reportTools);
    }

    private ToolCall build(String name, String description, List<ToolCallParameter> params, java.lang.reflect.Method method) {
        return Function.builder()
            .namespace("project")
            .sourceType("project")
            .name(name)
            .description(description)
            .object(dispatcher)
            .method(method)
            .parameters(params)
            .build();
    }

    private ToolCallParameter required(String name, String description) {
        return ToolCallParameter.builder()
            .name(name)
            .description(description)
            .type(ToolCallParameterType.STRING)
            .required(Boolean.TRUE)
            .build();
    }

    private java.lang.reflect.Method method(String name, Class<?>... parameterTypes) {
        var fullTypes = new Class<?>[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, fullTypes, 0, parameterTypes.length);
        fullTypes[parameterTypes.length] = ExecutionContext.class;
        try {
            return ProjectToolDispatcher.class.getMethod(name, fullTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("project tool method not found: " + name, e);
        }
    }
}
