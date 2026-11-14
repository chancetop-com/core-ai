package ai.core.server.quality;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.agent.AgentDefinitionStore;

import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.tool.ToolRegistryMetadata;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GenerateVideoTool;
import ai.core.tool.tools.GetVideoStatusTool;
import ai.core.tool.tools.PythonScriptTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.tool.tools.WebFetchTool;
import ai.core.tool.tools.WebSearchTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.server.util.IdLists;
import core.framework.inject.Inject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Metadata-only projection of "what this agent can actually execute". It reads the same builtin tool
 * registry runtime uses; it never connects an MCP server, runs a skill or calls a business API. A sub-agent
 * can provide a capability to its parent only through its published config, exactly the way runtime would
 * hand the work over.
 *
 * @author core-ai
 */
public class EffectiveCapabilityResolver {
    public static final String FILE_READ = "FILE_READ";
    public static final String FILE_WRITE = "FILE_WRITE";
    public static final String WEB_SEARCH = "WEB_SEARCH";
    public static final String WEB_FETCH = "WEB_FETCH";
    public static final String CODE_EXECUTE = "CODE_EXECUTE";
    public static final String IMAGE_GENERATE = "IMAGE_GENERATE";
    public static final String IMAGE_EDIT = "IMAGE_EDIT";
    public static final String VIDEO_GENERATE = "VIDEO_GENERATE";

    public static final List<String> CAPABILITIES = List.of(
        FILE_READ, FILE_WRITE, WEB_SEARCH, WEB_FETCH, CODE_EXECUTE, IMAGE_GENERATE, IMAGE_EDIT, VIDEO_GENERATE);

    @Inject
    AgentDefinitionStore agentDefinitionStore;

    /** Wired by ToolRegistryModule: the registry is bound after AgentDefinitionModule. */
    private ToolRegistryService toolRegistryService;

    /** Wired after GatewayModule, which loads after the agent module that binds the gate. */
    private GatewayRoutingEngine gatewayRoutingEngine;

    public void setToolRegistryService(ToolRegistryService toolRegistryService) {
        this.toolRegistryService = toolRegistryService;
    }

    public void setGatewayRoutingEngine(GatewayRoutingEngine gatewayRoutingEngine) {
        this.gatewayRoutingEngine = gatewayRoutingEngine;
    }

    public EffectiveCapabilityView resolve(List<ToolRef> toolRefs, List<String> subAgentIds) {
        if (toolRegistryService == null) {
            throw new IllegalStateException("effective capability resolver is not wired to the tool registry");
        }
        var names = new LinkedHashSet<>(ToolRegistryMetadata.effectiveBuiltinToolNames(toolRegistryService, toolRefs));
        var sources = new LinkedHashMap<String, String>();
        var capabilities = new LinkedHashMap<String, Boolean>();
        evaluate(names, capabilities, sources, null);

        for (var subAgentId : IdLists.clean(subAgentIds)) {
            var subAgent = agentDefinitionStore.get(subAgentId).orElse(null);
            if (subAgent == null || subAgent.publishedConfig == null) continue;
            var subNames = ToolRegistryMetadata.effectiveBuiltinToolNames(toolRegistryService, subAgent.publishedConfig.tools);
            evaluate(subNames, capabilities, sources, subAgentId);
        }
        return new EffectiveCapabilityView(List.copyOf(names), Map.copyOf(capabilities), Map.copyOf(sources));
    }

    /** Projection of an already-published config, used for sub-agent delegation checks. */
    public EffectiveCapabilityView resolvePublished(AgentPublishedConfig config) {
        if (config == null) return resolve(List.of(), List.of());
        return resolve(config.tools, config.subAgentIds);
    }

    private void evaluate(java.util.Collection<String> names, Map<String, Boolean> capabilities,
                          Map<String, String> sources, String subAgentId) {
        var present = new LinkedHashSet<String>(names);
        mark(capabilities, sources, FILE_READ, present.contains(ReadFileTool.TOOL_NAME), subAgentId,
            ReadFileTool.TOOL_NAME);
        var write = present.contains(WriteFileTool.TOOL_NAME)
            || present.contains(EditFileTool.TOOL_NAME) && present.contains(ReadFileTool.TOOL_NAME);
        mark(capabilities, sources, FILE_WRITE, write, subAgentId,
            present.contains(WriteFileTool.TOOL_NAME) ? WriteFileTool.TOOL_NAME : EditFileTool.TOOL_NAME);
        mark(capabilities, sources, WEB_SEARCH, present.contains(WebSearchTool.TOOL_NAME), subAgentId,
            WebSearchTool.TOOL_NAME);
        mark(capabilities, sources, WEB_FETCH, present.contains(WebFetchTool.TOOL_NAME), subAgentId,
            WebFetchTool.TOOL_NAME);
        mark(capabilities, sources, CODE_EXECUTE,
            present.contains(ShellCommandTool.TOOL_NAME) || present.contains(PythonScriptTool.TOOL_NAME),
            subAgentId, present.contains(ShellCommandTool.TOOL_NAME) ? ShellCommandTool.TOOL_NAME : PythonScriptTool.TOOL_NAME);

        var imageTool = present.contains(GenerateImageTool.TOOL_NAME);
        mark(capabilities, sources, IMAGE_GENERATE, imageTool && hasImageModel(GatewayEndpointType.IMAGE_GENERATION),
            subAgentId, GenerateImageTool.TOOL_NAME);
        mark(capabilities, sources, IMAGE_EDIT,
            imageTool && (hasImageModel(GatewayEndpointType.IMAGE_EDIT) || hasImageModel(GatewayEndpointType.IMAGE_GENERATION)),
            subAgentId, GenerateImageTool.TOOL_NAME);

        var videoReady = present.contains(GenerateVideoTool.TOOL_NAME)
            && present.contains(GetVideoStatusTool.TOOL_NAME)
            && hasImageModel(GatewayEndpointType.VIDEO_GENERATION);
        mark(capabilities, sources, VIDEO_GENERATE, videoReady, subAgentId, GenerateVideoTool.TOOL_NAME);
    }

    private boolean hasImageModel(GatewayEndpointType endpoint) {
        if (gatewayRoutingEngine == null) return false;
        try {
            return !gatewayRoutingEngine.mediaModelHints(endpoint).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void mark(Map<String, Boolean> capabilities, Map<String, String> sources, String capability,
                      boolean available, String subAgentId, String toolName) {
        if (available && !Boolean.TRUE.equals(capabilities.get(capability))) {
            capabilities.put(capability, Boolean.TRUE);
            sources.put(capability, subAgentId == null ? "builtin:" + toolName : "subagent:" + subAgentId);
        } else if (!capabilities.containsKey(capability)) {
            capabilities.put(capability, Boolean.FALSE);
        }
    }

    /** Copies only the fields the gate needs; used when the parent delegates execution to a sub-agent. */
    public AgentPublishedConfig publishedConfigOf(AgentDefinition definition) {
        if (definition == null || definition.publishedConfig == null) return null;
        return AgentExecutableConfigFactory.fromPublishedConfig(definition.publishedConfig);
    }

    public List<String> capabilityToolNames(String capability) {
        return switch (capability) {
            case FILE_READ -> List.of(ReadFileTool.TOOL_NAME);
            case FILE_WRITE -> List.of(WriteFileTool.TOOL_NAME, EditFileTool.TOOL_NAME);
            case WEB_SEARCH -> List.of(WebSearchTool.TOOL_NAME);
            case WEB_FETCH -> List.of(WebFetchTool.TOOL_NAME);
            case CODE_EXECUTE -> List.of(ShellCommandTool.TOOL_NAME, PythonScriptTool.TOOL_NAME);
            case IMAGE_GENERATE, IMAGE_EDIT -> List.of(GenerateImageTool.TOOL_NAME);
            case VIDEO_GENERATE -> List.of(GenerateVideoTool.TOOL_NAME, GetVideoStatusTool.TOOL_NAME);
            default -> List.of();
        };
    }
}








