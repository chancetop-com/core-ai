package ai.core.server.quality;
import ai.core.server.domain.AgentDefinition;

import ai.core.prompt.engines.MustachePromptTemplate;
import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.DefinitionType;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.util.IdLists;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the immutable publish candidate. Managed prompt resolution happens here exactly once; evaluation
 * and commit both use the object returned by this builder.
 *
 * @author core-ai
 */
public class PublishCandidateBuilder {
    private static final String SENTINEL_PREFIX = "__quality_system_time__";

    @Inject
    SystemPromptService systemPromptService;

    public PublishCandidate build(AgentDefinition entity) {
        int revision = entity.definitionRevision == null ? 0 : entity.definitionRevision;
        entity.subAgentIds = IdLists.cleanOrNull(entity.subAgentIds);
        entity.skillIds = IdLists.cleanOrNull(entity.skillIds);

        var config = AgentExecutableConfigFactory.fromEditableDefinition(entity);
        AgentDependencyAccessPolicy.markPublishedSkillsValidated(config);

        var managed = !isBlank(entity.systemPromptId);
        if (managed) {
            try {
                config.systemPrompt = systemPromptService.resolveContent(entity.systemPromptId);
                config.systemPromptId = null;
            } catch (RuntimeException e) {
                // the broken managed prompt stays a reference failure; publish must not fall back to an
                // unrelated inline prompt and silently change what the author asked for
                config.systemPrompt = entity.systemPrompt;
            }
        }

        var prompt = promptView(config, managed, entity.systemPromptId);
        var lines = splitLines(prompt.systemPrompt());
        return new PublishCandidate(entity, config, entity.name, entity.description,
            QualityHashes.candidateHash(config), QualityHashes.metadataHash(entity.name, entity.description),
            revision, prompt, lines,
            managed ? "/system_prompt_id" : "/system_prompt",
            managed ? "MANAGED" : "INLINE");
    }

    public QualityPromptView promptView(AgentPublishedConfig config, boolean managed, String promptId) {
        var content = config.systemPrompt == null ? "" : config.systemPrompt;
        return new QualityPromptView(content, config.inputTemplate,
            managed ? "MANAGED" : "INLINE", promptId, managed,
            systemTimeInjected(content), QualityHashes.textHash(content));
    }

    /**
     * Render the real template with a sentinel in the same variable name runtime supplies. A literal
     * occurrence that is never rendered (inside an unused section, escaped, or in another field) stays
     * visible in the output and therefore does not count.
     */
    public boolean systemTimeInjected(String template) {
        if (isBlank(template)) return false;
        var sentinel = SENTINEL_PREFIX + UUID.randomUUID();
        try {
            var rendered = MustachePromptTemplate.compile(template, Map.of("system.time", sentinel));
            return rendered.contains(sentinel) && !rendered.contains("{{system.time}}");
        } catch (RuntimeException e) {
            return false;
        }
    }

    public List<QualityPromptLine> splitLines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        var lines = new ArrayList<QualityPromptLine>();
        var parts = text.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            lines.add(new QualityPromptLine(i + 1, parts[i]));
        }
        return List.copyOf(lines);
    }

    public boolean appliesToPrompt(DefinitionType type) {
        return type == DefinitionType.AGENT || type == DefinitionType.LLM_CALL;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}





