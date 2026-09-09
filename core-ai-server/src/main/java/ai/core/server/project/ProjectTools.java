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
 * Registers the {@code project-report} builtin tool group (append_report_section) that the builtin
 * report-renderer agent mounts. The analysis pipeline itself has no agent-facing tools: attribution
 * and subject analysis are driven by the jobs through the LLM_CALL writer definitions directly.
 *
 * @author stephen
 */
public class ProjectTools {
    public static final String REPORT_TOOL_SET_NAME = "builtin:project-report";

    @Inject
    ProjectToolDispatcher dispatcher;
    @Inject
    ToolRegistryService toolRegistryService;

    public void initialize() {
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
