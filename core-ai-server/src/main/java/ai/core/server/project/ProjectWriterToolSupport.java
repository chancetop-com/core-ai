package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.llmcall.LLMCallTool;
import ai.core.server.run.LLMCallExecutor;
import ai.core.tool.ToolCall;

import java.util.Set;

/**
 * Bridges the tool-resolution layer (which runs in every agent's tool registry) to the project
 * module's writer applier without a bind-time dependency: the resolution service asks here whether
 * a resolved LLM_CALL definition is one of the builtin project writers, and wraps it with an
 * apply-on-execute tool when the factory has been registered by ProjectModule. Falls back to a
 * plain LLM_CALL tool (raw output, no apply) in contexts where the project module is absent.
 *
 * @author stephen
 */
public final class ProjectWriterToolSupport {
    public static final Set<String> PROJECT_WRITER_IDS = Set.of(
        "builtin-" + ProjectBuiltinAgents.ATTRIBUTOR,
        "builtin-" + ProjectBuiltinAgents.SUBJECT_ANALYZER);

    private static volatile ProjectWriterToolFactory factory;

    public static void setFactory(ProjectWriterToolFactory toolFactory) {
        factory = toolFactory;
    }

    public static boolean isProjectWriter(String definitionId) {
        return definitionId != null && PROJECT_WRITER_IDS.contains(definitionId);
    }

    public static ToolCall wrap(String definitionId, AgentDefinition definition, LLMCallExecutor executor) {
        if (factory == null) return LLMCallTool.create(definition, executor);
        return factory.create(definitionId, definition, executor);
    }

    private ProjectWriterToolSupport() {
    }
}
