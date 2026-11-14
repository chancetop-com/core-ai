package ai.core.server.quality;

import ai.core.mcp.client.McpServerConfig;
import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.skill.SkillService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryMetadata;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata-only reference resolution for A1. It never starts an MCP session, downloads a dataset or runs a
 * skill: a database error becomes ERROR, a missing entity becomes FAIL, and an unreadable others' resource
 * is reported as unavailable without its name.
 *
 * @author core-ai
 */
public class QualityReferenceResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(QualityReferenceResolver.class);

    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    SkillService skillService;
    @Inject
    SystemPromptService systemPromptService;

    /** Wired by DatasetModule, which loads after the module that binds the gate. */
    private DatasetService datasetService;

    /** Wired by ToolRegistryModule. */
    private ToolRegistryService toolRegistryService;

    public void setToolRegistryService(ToolRegistryService toolRegistryService) {
        this.toolRegistryService = toolRegistryService;
    }

    public void setDatasetService(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public List<ResolvedReference> resolve(AgentDefinition entity, AgentPublishedConfig config) {
        var refs = new ArrayList<ResolvedReference>();
        resolveTools(config.tools, refs);
        resolveSkills(config.skillIds, refs);
        resolveSubAgents(config.subAgentIds, refs);
        resolveDatasets(config.datasetConfig, refs);
        if (config.systemPromptId != null && !config.systemPromptId.isBlank()) {
            refs.add(resolveSystemPrompt(config.systemPromptId));
        }
        return refs;
    }

    private void resolveTools(List<ToolRef> tools, List<ResolvedReference> refs) {
        if (tools == null) return;
        for (int i = 0; i < tools.size(); i++) {
            var ref = tools.get(i);
            if (ref == null || ref.id == null) {
                refs.add(new ResolvedReference("TOOL", null, null, null,
                    QualityReferenceStatus.MISSING, "EMPTY_REFERENCE", "/tools[" + i + "]", i));
                continue;
            }
            if (AgentDependencyAccessPolicy.isLlmCallRef(ref)) {
                resolveLlmCall(ref, i, refs);
                continue;
            }
            var type = ref.type;
            if (type == ToolSourceType.MCP || ref.id.startsWith("mcp-tool:") || ref.id.startsWith("config:")) {
                resolveMcpServer(ref, i, refs);
                continue;
            }
            if (type == ToolSourceType.AGENT) {
                refs.add(resolvePublishedAgent(ref.id, "TOOL", "/tools[" + i + "]", i));
                continue;
            }
            if (type == ToolSourceType.API) {
                refs.add(resolveApi(ref, i));
                continue;
            }
            refs.add(resolveBuiltin(ref, i));
        }
    }

    private ResolvedReference resolveBuiltin(ToolRef ref, int index) {
        if (toolRegistryService == null) {
            return new ResolvedReference("BUILTIN", ref.id, ref.source, null,
                QualityReferenceStatus.ERROR, "TOOL_REGISTRY_UNAVAILABLE", "/tools[" + index + "]", index);
        }
        if (ToolRegistryMetadata.builtinRefAvailable(toolRegistryService, ref)) {
            return new ResolvedReference("BUILTIN", ref.id, ref.source, ref.id,
                QualityReferenceStatus.AVAILABLE, null, "/tools[" + index + "]", index);
        }
        return new ResolvedReference("BUILTIN", ref.id, ref.source, ref.id,
            QualityReferenceStatus.MISSING, "BUILTIN_NOT_REGISTERED", "/tools[" + index + "]", index);
    }

    private ResolvedReference resolveApi(ToolRef ref, int index) {
        if (toolRegistryService == null) {
            return new ResolvedReference("API", ref.id, ref.source, null,
                QualityReferenceStatus.ERROR, "TOOL_REGISTRY_UNAVAILABLE", "/tools[" + index + "]", index);
        }
        if (ToolRegistryMetadata.findToolEntry(toolRegistryService, ref.id) != null || !ToolRegistryMetadata.apiToolNames(toolRegistryService, ref.id).isEmpty()) {
            return new ResolvedReference("API", ref.id, ref.source, ref.id,
                QualityReferenceStatus.AVAILABLE, null, "/tools[" + index + "]", index);
        }
        return new ResolvedReference("API", ref.id, ref.source, ref.id,
            QualityReferenceStatus.MISSING, "API_TOOL_NOT_FOUND", "/tools[" + index + "]", index);
    }

    private void resolveMcpServer(ToolRef ref, int index, List<ResolvedReference> refs) {
        var serverId = ref.id;
        var toolName = (String) null;
        var parsed = ToolRef.parseMcpToolId(ref.id, ref.source);
        if (parsed != null) {
            serverId = parsed.serverId();
            toolName = parsed.toolName();
        }
        if (serverId == null || serverId.isBlank()) {
            refs.add(new ResolvedReference("MCP", ref.id, ref.source, null,
                QualityReferenceStatus.MISSING, "MCP_SERVER_NOT_RESOLVED", "/tools[" + index + "]", index));
            return;
        }
        var entry = findMcpEntry(serverId);
        if (entry == null) {
            refs.add(new ResolvedReference("MCP", ref.id, ref.source, serverId,
                QualityReferenceStatus.MISSING, "MCP_SERVER_NOT_FOUND", "/tools[" + index + "]", index));
            return;
        }
        if (!Boolean.TRUE.equals(entry.enabled)) {
            refs.add(new ResolvedReference("MCP", ref.id, ref.source, entry.name,
                QualityReferenceStatus.DISABLED, "MCP_SERVER_DISABLED", "/tools[" + index + "]", index));
            return;
        }
        if (entry.type != ToolType.MCP) {
            refs.add(new ResolvedReference("MCP", ref.id, ref.source, entry.name,
                QualityReferenceStatus.NOT_USABLE, "TYPE_MISMATCH", "/tools[" + index + "]", index));
            return;
        }
        if (!parseable(entry)) {
            refs.add(new ResolvedReference("MCP", ref.id, ref.source, entry.name,
                QualityReferenceStatus.NOT_USABLE, "MCP_CONFIG_INVALID", "/tools[" + index + "]", index));
            return;
        }
        refs.add(new ResolvedReference("MCP", ref.id, ref.source, entry.name,
            QualityReferenceStatus.AVAILABLE, toolName == null ? null : "TOOL:" + toolName, "/tools[" + index + "]", index));
    }

    private boolean parseable(ToolRegistryEntry entry) {
        try {
            var raw = new LinkedHashMap<String, Object>();
            if (entry.config != null) entry.config.forEach(raw::put);
            McpServerConfig.fromMap(entry.name, raw);
            return true;
        } catch (RuntimeException e) {
            LOGGER.debug("mcp server config is not parseable, id={}, error={}", entry.id, e.getMessage());
            return false;
        }
    }

    private ToolRegistryEntry findMcpEntry(String idOrName) {
        if (toolRegistryService == null) return null;
        var direct = ToolRegistryMetadata.findToolEntry(toolRegistryService, idOrName);
        if (direct != null && direct.type == ToolType.MCP) return direct;
        var config = ToolRegistryMetadata.findToolEntry(toolRegistryService, "config:" + idOrName);
        if (config != null && config.type == ToolType.MCP) return config;
        for (var entry : toolRegistryService.listTools(null)) {
            if (entry.type == ToolType.MCP && idOrName.equals(entry.name)) return entry;
        }
        return null;
    }

    private void resolveLlmCall(ToolRef ref, int index, List<ResolvedReference> refs) {
        String definitionId;
        try {
            definitionId = AgentDependencyAccessPolicy.requireLlmCallDefinitionId(ref);
        } catch (IllegalArgumentException e) {
            refs.add(new ResolvedReference("LLM_CALL", ref.id, ref.source, null,
                QualityReferenceStatus.MISSING, "MALFORMED_REFERENCE", "/tools[" + index + "]", index));
            return;
        }
        var definition = agentDefinitionStore.get(definitionId).orElse(null);
        if (definition == null) {
            refs.add(new ResolvedReference("LLM_CALL", ref.id, ref.source, definitionId,
                QualityReferenceStatus.MISSING, "LLM_CALL_NOT_FOUND", "/tools[" + index + "]", index));
            return;
        }
        if (!AgentDependencyAccessPolicy.hasUsablePublishedLlmCall(definition)) {
            refs.add(new ResolvedReference("LLM_CALL", ref.id, ref.source, definitionId,
                QualityReferenceStatus.VERSION_UNAVAILABLE, "LLM_CALL_NOT_PUBLISHED", "/tools[" + index + "]", index));
            return;
        }
        refs.add(new ResolvedReference("LLM_CALL", ref.id, ref.source, definition.name,
            QualityReferenceStatus.AVAILABLE, null, "/tools[" + index + "]", index));
    }

    private ResolvedReference resolvePublishedAgent(String agentId, String type, String fieldPath, int index) {
        var definition = agentDefinitionStore.get(agentId).orElse(null);
        if (definition == null) {
            return new ResolvedReference(type, agentId, null, null,
                QualityReferenceStatus.MISSING, "AGENT_NOT_FOUND", fieldPath, index);
        }
        if (!AgentDependencyAccessPolicy.hasUsablePublishedSubAgent(definition)) {
            return new ResolvedReference(type, agentId, null, agentId,
                QualityReferenceStatus.VERSION_UNAVAILABLE, "AGENT_NOT_PUBLISHED", fieldPath, index);
        }
        return new ResolvedReference(type, agentId, null, definition.name,
            QualityReferenceStatus.AVAILABLE, null, fieldPath, index);
    }

    private void resolveSkills(List<String> skillIds, List<ResolvedReference> refs) {
        var clean = IdLists.clean(skillIds);
        for (int i = 0; i < clean.size(); i++) {
            var skill = skillService.findOrNull(clean.get(i));
            refs.add(skill == null
                ? new ResolvedReference("SKILL", clean.get(i), null, null,
                    QualityReferenceStatus.MISSING, "SKILL_NOT_FOUND", "/skill_ids[" + i + "]", i)
                : new ResolvedReference("SKILL", clean.get(i), null, skill.name,
                    QualityReferenceStatus.AVAILABLE, null, "/skill_ids[" + i + "]", i));
        }
    }

    private void resolveSubAgents(List<String> subAgentIds, List<ResolvedReference> refs) {
        var clean = IdLists.clean(subAgentIds);
        for (int i = 0; i < clean.size(); i++) {
            refs.add(resolvePublishedAgent(clean.get(i), "SUBAGENT", "/subagent_ids[" + i + "]", i));
        }
    }

    private void resolveDatasets(List<ai.core.server.domain.AgentDatasetConfig> datasets, List<ResolvedReference> refs) {
        if (datasets == null) return;
        for (int i = 0; i < datasets.size(); i++) {
            var config = datasets.get(i);
            if (datasetService == null) {
                refs.add(new ResolvedReference("DATASET", config == null ? null : config.datasetId, null, null,
                    QualityReferenceStatus.ERROR, "DATASET_STORE_UNAVAILABLE", "/dataset_config[" + i + "]", i));
                continue;
            }
            var dataset = config == null || config.datasetId == null ? null : datasetService.get(config.datasetId);
            refs.add(dataset == null
                ? new ResolvedReference("DATASET", config == null ? null : config.datasetId, null, null,
                    QualityReferenceStatus.MISSING, "DATASET_NOT_FOUND", "/dataset_config[" + i + "]", i)
                : new ResolvedReference("DATASET", dataset.id, null, dataset.name,
                    QualityReferenceStatus.AVAILABLE, null, "/dataset_config[" + i + "]", i));
        }
    }

    private ResolvedReference resolveSystemPrompt(String promptId) {
        try {
            systemPromptService.resolveContent(promptId);
            return new ResolvedReference("SYSTEM_PROMPT", promptId, null, promptId,
                QualityReferenceStatus.AVAILABLE, null, "/system_prompt_id", -1);
        } catch (RuntimeException e) {
            return new ResolvedReference("SYSTEM_PROMPT", promptId, null, promptId,
                QualityReferenceStatus.MISSING, "MANAGED_PROMPT_NOT_FOUND", "/system_prompt_id", -1);
        }
    }

    /** Reference names of configured resources, used by B3 candidate matching. */
    public Map<String, String> configuredResourceNames(AgentDefinition entity, AgentPublishedConfig config) {
        var names = new LinkedHashMap<String, String>();
        if (config.tools != null) {
            for (var ref : config.tools) {
                if (ref == null || ref.id == null) continue;
                names.putIfAbsent(ref.id, "TOOL");
            }
        }
        if (config.skillIds != null) {
            for (var id : IdLists.clean(config.skillIds)) {
                var skill = skillService.findOrNull(id);
                if (skill != null) names.putIfAbsent(skill.name, "SKILL");
            }
        }
        if (config.subAgentIds != null) {
            for (var id : IdLists.clean(config.subAgentIds)) {
                var definition = agentDefinitionStore.get(id).orElse(null);
                if (definition != null) names.putIfAbsent(definition.name, "SUBAGENT");
            }
        }
        if (config.datasetConfig != null && datasetService != null) {
            for (var datasetConfig : config.datasetConfig) {
                var dataset = datasetConfig == null || datasetConfig.datasetId == null ? null : datasetService.get(datasetConfig.datasetId);
                if (dataset != null) names.putIfAbsent(dataset.name, "DATASET");
            }
        }
        return names;
    }
}



