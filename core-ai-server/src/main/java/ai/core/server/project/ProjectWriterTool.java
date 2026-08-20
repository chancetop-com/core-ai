package ai.core.server.project;

import ai.core.agent.ExecutionContext;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.run.LLMCallExecutor;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;

/**
 * The builtin project writers mounted as native LLM_CALL tools on the project-agent: the agent
 * composes the input (subjects + material digest, or playbook + subject state + material) and
 * calls the tool; the tool runs the tunable LLM_CALL definition and APPLIES the structured result
 * (attribution upserts / subject state writes + cursor advance), returning a short confirmation.
 *
 * @author stephen
 */
public final class ProjectWriterTool extends ToolCall {
    static final String ATTRIBUTOR_ID = "builtin-" + ProjectBuiltinAgents.ATTRIBUTOR;
    static final String ANALYZER_ID = "builtin-" + ProjectBuiltinAgents.SUBJECT_ANALYZER;

    static ProjectWriterTool create(String definitionId, AgentDefinition definition, LLMCallExecutor executor,
                                    ProjectAttributionStage attributionStage, ProjectSubjectAnalysisStage subjectStage,
                                    ProjectScopeLoader scopeLoader) {
        var tool = new ProjectWriterTool(definitionId, definition, executor, attributionStage, subjectStage, scopeLoader);
        new Builder(tool, definitionId, definition).build();
        return tool;
    }

    private final String definitionId;
    private final AgentDefinition definition;
    private final LLMCallExecutor executor;
    private final ProjectAttributionStage attributionStage;
    private final ProjectSubjectAnalysisStage subjectStage;
    private final ProjectScopeLoader scopeLoader;

    private ProjectWriterTool(String definitionId, AgentDefinition definition, LLMCallExecutor executor,
                              ProjectAttributionStage attributionStage, ProjectSubjectAnalysisStage subjectStage,
                              ProjectScopeLoader scopeLoader) {
        this.definitionId = definitionId;
        this.definition = definition;
        this.executor = executor;
        this.attributionStage = attributionStage;
        this.subjectStage = subjectStage;
        this.scopeLoader = scopeLoader;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        try {
            var args = parseArguments(arguments);
            var query = getStringValue(args, "query");
            if (query == null || query.isBlank()) {
                return ToolCallResult.failed("Parameter 'query' is required for " + getName());
            }
            var scope = scopeLoader.requireOwnedScope(context);
            String subjectId = null;
            if (isAnalyzer()) {
                subjectId = getStringValue(args, "subject_id");
                if (subjectId == null || subjectId.isBlank()) {
                    return ToolCallResult.failed("Parameter 'subject_id' is required for " + getName());
                }
                var subject = scopeLoader.requireSubject(scope.projectId(), subjectId);
                if (subject == null) return ToolCallResult.failed("subject not found in project: " + subjectId);
            }
            var result = executor.execute(definition, query);
            int applied;
            if (isAnalyzer()) {
                applied = subjectStage.apply(scope.projectId(), subjectId, result.output());
            } else {
                applied = attributionStage.apply(scope.projectId(), result.output());
            }
            return ToolCallResult.completed(isAnalyzer()
                    ? "Subject analysis applied: " + applied + " record(s) updated for subject " + subjectId
                    : "Attribution applied: " + applied + " target(s) attributed")
                .withToolName(getName())
                .withStats("llm_call_input_tokens", result.inputTokens())
                .withStats("llm_call_output_tokens", result.outputTokens());
        } catch (Exception e) {
            return ToolCallResult.failed("Project writer '" + getName() + "' execution error: " + e.getMessage(), e)
                .withToolName(getName());
        }
    }

    @Override
    public long getTimeoutMs() {
        var config = definition.publishedConfig;
        var timeoutSeconds = config != null && config.timeoutSeconds != null ? config.timeoutSeconds : definition.timeoutSeconds;
        return timeoutSeconds != null ? timeoutSeconds * 1000L : DEFAULT_TIMEOUT_MS;
    }

    private boolean isAnalyzer() {
        return ANALYZER_ID.equals(definitionId);
    }

    /**
     * Resolves the caller-owned project (and optionally a subject) without touching the DB itself;
     * implemented by the factory so the tool stays free of collection dependencies.
     */
    public interface ProjectScopeLoader {
        Scope requireOwnedScope(ExecutionContext context);

        ProjectSubject requireSubject(String projectId, String subjectId);

        record Scope(String projectId, Project project) {
        }
    }

    // the main agent composes the input itself; the writer only needs the digest + the subject id
    private static class Builder extends ToolCall.Builder<Builder, ProjectWriterTool> {
        private final ProjectWriterTool tool;
        private final String definitionId;
        private final AgentDefinition definition;

        Builder(ProjectWriterTool tool, String definitionId, AgentDefinition definition) {
            this.tool = tool;
            this.definitionId = definitionId;
            this.definition = definition;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(sanitizeName(definition.name));
            description(defaultDescription());
            sourceType("llm-call");
            var params = new java.util.ArrayList<ToolCallParameter>();
            params.add(param("query", inputDescription(), Boolean.TRUE));
            if (ATTRIBUTOR_ID.equals(definitionId)) {
                params.add(param("project_id", "Project ID (auto-injected, normally omitted)", Boolean.FALSE));
            } else {
                params.add(param("subject_id", "The subject id the analysis applies to", Boolean.TRUE));
                params.add(param("project_id", "Project ID (auto-injected, normally omitted)", Boolean.FALSE));
            }
            parameters(params);
            build(tool);
        }

        private ToolCallParameter param(String name, String description, Boolean required) {
            return ToolCallParameter.builder()
                .name(name)
                .description(description)
                .type(ToolCallParameterType.STRING)
                .required(required)
                .build();
        }

        private String inputDescription() {
            return ATTRIBUTOR_ID.equals(definitionId)
                ? "SUBJECTS list (id: name) followed by a digest of the new unattributed targets (sessions/runs/workflow runs) you found via the search tools. The result is applied automatically."
                : "The subject analysis input: playbook (if any), the subject's name/description, its current state (status/KPIs/action items) and a digest of its new attributed material. The result is applied automatically.";
        }

        private String defaultDescription() {
            var base = definition.description != null && !definition.description.isBlank()
                ? definition.description
                : "Call the builtin project writer " + definition.name;
            return base + " Output is parsed and applied to the project automatically — you only need to prepare a good input.";
        }

        private String sanitizeName(String name) {
            return name == null ? "llm-call" : name.trim().replaceAll("[\\s<|\\\\/>]+", "-");
        }
    }
}
