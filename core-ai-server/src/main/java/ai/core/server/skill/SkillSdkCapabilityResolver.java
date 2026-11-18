package ai.core.server.skill;

import ai.core.server.agent.AgentDefinitionQuery;
import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.tool.ToolRegistryService;
import ai.core.skill.SkillCapability;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillSdkCapabilityManifest;
import ai.core.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the supported declarations of loaded skills into concrete session capabilities.
 * <p>
 * This deliberately bypasses the agent's {@code ToolRegistry}: a grant-only capability is
 * callable from the session SDK surface but is never advertised to the model.
 *
 * @author core-ai
 */
public class SkillSdkCapabilityResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillSdkCapabilityResolver.class);
    private static final String KIND_MCP = "mcp";
    private static final String KIND_API = "api";

    private final ToolRegistryService toolRegistryService;
    private final PermissionService permissionService;
    private final AgentDefinitionStore agentDefinitionStore;

    public SkillSdkCapabilityResolver(ToolRegistryService toolRegistryService, PermissionService permissionService) {
        this(toolRegistryService, permissionService, null);
    }

    public SkillSdkCapabilityResolver(ToolRegistryService toolRegistryService, PermissionService permissionService,
                                      AgentDefinitionStore agentDefinitionStore) {
        this.toolRegistryService = toolRegistryService;
        this.permissionService = permissionService;
        this.agentDefinitionStore = agentDefinitionStore;
    }

    /**
     * @param skills skill id to metadata for every skill currently assigned to the session
     */
    public List<SkillCapability> resolve(String sessionId, String userId, Map<String, SkillMetadata> skills) {
        if (toolRegistryService == null || skills == null || skills.isEmpty()) return List.of();
        var resolution = new Resolution(sessionId, userId);
        for (var skill : skills.entrySet()) {
            var skillId = skill.getKey();
            var metadata = skill.getValue();
            if (metadata == null) continue;
            var manifest = SkillSdkCapabilityManifest.parse(metadata.getAllowedTools(), metadata.getMetadata());
            if (!manifest.enabled()) continue;
            for (var entry : manifest.entries()) {
                resolveEntry(resolution, skillId, metadata, entry);
            }
        }
        return List.copyOf(resolution.result);
    }

    private void resolveEntry(Resolution resolution, String skillId, SkillMetadata metadata,
                              SkillSdkCapabilityManifest.Entry entry) {
        if (!canGrant(resolution.userId, entry)) return;
        var ref = toToolRef(resolution.userId, entry);
        if (ref == null) {
            LOGGER.debug("skip unresolved skill capability, skill={}, entry={}", metadata.getQualifiedName(), entry.raw());
            return;
        }
        try {
            var tools = toolRegistryService.resolveToolRefs(List.of(ref), resolution.sessionId, resolution.userId);
            for (var tool : tools) {
                var capability = capability(skillId, metadata, entry, tool);
                if (resolution.seen.add(capability.skillId() + "|" + capability.refId() + "|" + tool.getName())) {
                    resolution.result.add(capability);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to resolve skill capability, skill={}, entry={}, error={}",
                    metadata.getQualifiedName(), entry.raw(), e.getMessage());
        }
    }

    private boolean canGrant(String userId, SkillSdkCapabilityManifest.Entry entry) {
        if (permissionService == null) return true;
        if (userId == null) return false;
        var parts = entry.parts();
        if (KIND_MCP.equals(entry.kind())) {
            if (parts.size() < 2 || !permissionService.has(userId, PermissionCodes.MCP_CALL)) return false;
            return !permissionService.isApiUser(userId)
                    || permissionService.checkResource(userId, PermissionService.RESOURCE_TYPE_MCP_SERVER, parts.get(0));
        }
        if ("api-tool".equals(entry.kind())) {
            if (parts.size() < 3 || !permissionService.has(userId, PermissionCodes.APITOOL_CALL)) return false;
            return !permissionService.isApiUser(userId)
                    || permissionService.checkResource(userId, PermissionService.RESOURCE_TYPE_API_APP, parts.get(0));
        }
        if ("llm-call".equals(entry.kind())) {
            return parts.size() == 1 && findLlmCall(userId, parts.getFirst()) != null;
        }
        return false;
    }

    private ToolRef toToolRef(String userId, SkillSdkCapabilityManifest.Entry entry) {
        var parts = entry.parts();
        if (KIND_MCP.equals(entry.kind()) && parts.size() == 2) {
            return ToolRef.of("mcp-tool:" + parts.get(0) + ":" + parts.get(1), ToolSourceType.MCP);
        }
        if ("api-tool".equals(entry.kind()) && parts.size() == 3) {
            return ToolRef.of("api-operation:" + parts.get(0) + ":" + parts.get(1) + ":" + parts.get(2), ToolSourceType.API);
        }
        if ("llm-call".equals(entry.kind()) && parts.size() == 1) {
            var definition = findLlmCall(userId, parts.getFirst());
            return definition == null ? null : ToolRef.of(ToolRef.LLM_CALL_PREFIX + definition.id, ToolSourceType.LLM_CALL);
        }
        return null;
    }

    private AgentDefinition findLlmCall(String userId, String name) {
        if (agentDefinitionStore == null || userId == null || name == null || name.isBlank()) return null;
        var direct = new AgentDefinitionQuery();
        direct.scope = AgentDefinitionQuery.Scope.DIRECT;
        direct.userId = userId;
        direct.name = name;
        direct.type = "LLM_CALL";
        var owned = agentDefinitionStore.findOne(direct).orElse(null);
        if (owned != null) {
            try {
                return AgentDependencyAccessPolicy.executableLlmCall(owned, userId);
            } catch (RuntimeException e) {
                return null;
            }
        }
        var published = new AgentDefinitionQuery();
        published.scope = AgentDefinitionQuery.Scope.ALL;
        published.name = name;
        published.type = "LLM_CALL";
        published.status = "PUBLISHED";
        var matches = agentDefinitionStore.find(published);
        if (matches.size() != 1) return null;
        var candidate = matches.getFirst();
        return AgentDependencyAccessPolicy.hasUsablePublishedLlmCall(candidate) ? candidate : null;
    }

    private SkillCapability capability(String skillId, SkillMetadata metadata, SkillSdkCapabilityManifest.Entry entry,
                                       ToolCall tool) {
        var parts = entry.parts();
        if (KIND_MCP.equals(entry.kind())) {
            return new SkillCapability(skillId, metadata.getQualifiedName(), KIND_MCP,
                    "mcp:" + parts.get(0) + ":" + parts.get(1),
                    parts.get(0) + "/" + parts.get(1), parts.get(0), tool);
        }
        if ("llm-call".equals(entry.kind())) {
            return new SkillCapability(skillId, metadata.getQualifiedName(), "llm_call",
                    tool.getName(), tool.getName(), null, tool);
        }
        return new SkillCapability(skillId, metadata.getQualifiedName(), KIND_API,
                String.join("/", parts), String.join("/", parts), parts.get(0), tool);
    }

    private static final class Resolution {
        final String sessionId;
        final String userId;
        final List<SkillCapability> result = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        Resolution(String sessionId, String userId) {
            this.sessionId = sessionId;
            this.userId = userId;
        }
    }
}
