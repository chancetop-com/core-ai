package ai.core.server.agentbuilder;

import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.agent.AgentPublishService;
import ai.core.server.agent.PublishOptions;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.migration.SchemaMigrationVDefaultAgent;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.selfharness.SelfHarnessTools;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCall;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent builder session tools — now delegates agent management tools to the
 * {@link SelfHarnessTools} builtin group so they can be dynamically configured
 * for any agent, not just the builder.
 */
public class AgentBuilderTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentBuilderTools.class);
    private static final String SEED_VERSION = "20261120002";

    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    AgentPublishService agentPublishService;

    public void initialize() {
        // Mongo seeding does not update already-migrated PostgreSQL agents; upgrade the active store once.
        var definition = agentDefinitionStore.get(SchemaMigrationVDefaultAgent.AGENT_BUILDER_ID).orElse(null);
        if (definition == null || !Boolean.TRUE.equals(definition.systemDefault) || definition.type != DefinitionType.AGENT
            || SEED_VERSION.equals(definition.systemSeedVersion)) return;
        definition.description = SchemaMigrationVDefaultAgent.AGENT_BUILDER_DESCRIPTION;
        definition.systemPrompt = SchemaMigrationVDefaultAgent.agentBuilderSystemPrompt();
        definition.systemPromptId = null;
        var refs = new ArrayList<ToolRef>(definition.tools != null ? definition.tools : List.of());
        for (var name : SchemaMigrationVDefaultAgent.AGENT_BUILDER_TOOL_NAMES) {
            var id = "builtin:self-harness:" + name;
            if (refs.stream().noneMatch(ref -> id.equals(ref.id))) {
                refs.add(ToolRef.of(id, ToolSourceType.BUILTIN, "Self Harness"));
            }
        }
        definition.tools = refs;
        definition.systemSeedVersion = SEED_VERSION;
        RuntimeException failure = null;
        boolean committed = false;
        try {
            if (definition.status == AgentStatus.PUBLISHED) {
                agentPublishService.publish(definition, "system", PublishOptions.none());
                committed = true;
            } else {
                committed = agentDefinitionStore.replaceIfRevision(definition,
                        definition.definitionRevision != null ? definition.definitionRevision : 0);
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        if (committed) return;
        // every pod runs this on startup; the loser of the CAS must not fail startup after the winner committed
        var current = agentDefinitionStore.get(SchemaMigrationVDefaultAgent.AGENT_BUILDER_ID).orElse(null);
        if (current != null && SEED_VERSION.equals(current.systemSeedVersion)) {
            LOGGER.info("agent builder seed already upgraded by another instance");
            return;
        }
        if (failure != null) throw failure;
        LOGGER.warn("agent builder changed during seed upgrade, retrying on the next start");
    }

    public List<ToolCall> tools() {
        var selfHarnessRef = ToolRef.of(SelfHarnessTools.TOOL_SET_NAME, ToolSourceType.BUILTIN);
        var allToolsRef = ToolRef.of("builtin-all", ToolSourceType.BUILTIN);
        return toolRegistryService.resolveToolRefs(List.of(selfHarnessRef, allToolsRef));
    }
}
