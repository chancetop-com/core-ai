package ai.core.server.skill;

import ai.core.server.apiuser.PermissionService;
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

    public SkillSdkCapabilityResolver(ToolRegistryService toolRegistryService, PermissionService permissionService) {
        this.toolRegistryService = toolRegistryService;
        this.permissionService = permissionService;
    }

    /**
     * @param skills skill id to metadata for every skill currently assigned to the session
     */
    public List<SkillCapability> resolve(String sessionId, String userId, Map<String, SkillMetadata> skills) {
        if (toolRegistryService == null || skills == null || skills.isEmpty()) return List.of();
        var result = new ArrayList<SkillCapability>();
        var seen = new HashSet<String>();
        for (var skill : skills.entrySet()) {
            var skillId = skill.getKey();
            var metadata = skill.getValue();
            if (metadata == null) continue;
            var manifest = SkillSdkCapabilityManifest.parse(metadata.getAllowedTools(), metadata.getMetadata());
            if (!manifest.enabled()) continue;
            for (var entry : manifest.entries()) {
                if (!canGrant(userId, entry)) continue;
                var ref = toToolRef(entry);
                if (ref == null) {
                    LOGGER.debug("skip unresolved skill capability, skill={}, entry={}", metadata.getQualifiedName(), entry.raw());
                    continue;
                }
                try {
                    var tools = toolRegistryService.resolveToolRefs(List.of(ref), sessionId, userId);
                    for (var tool : tools) {
                        var capability = capability(skillId, metadata, entry, tool);
                        if (seen.add(capability.skillId() + "|" + capability.refId() + "|" + tool.getName())) {
                            result.add(capability);
                        }
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("failed to resolve skill capability, skill={}, entry={}, error={}",
                            metadata.getQualifiedName(), entry.raw(), e.getMessage());
                }
            }
        }
        return List.copyOf(result);
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
        // LLM_CALL name -> definition id resolution is not wired yet; do not silently grant it.
        return false;
    }

    private ToolRef toToolRef(SkillSdkCapabilityManifest.Entry entry) {
        var parts = entry.parts();
        if (KIND_MCP.equals(entry.kind()) && parts.size() == 2) {
            return ToolRef.of("mcp-tool:" + parts.get(0) + ":" + parts.get(1), ToolSourceType.MCP);
        }
        if ("api-tool".equals(entry.kind()) && parts.size() == 3) {
            return ToolRef.of("api-operation:" + parts.get(0) + ":" + parts.get(1) + ":" + parts.get(2), ToolSourceType.API);
        }
        return null;
    }

    private SkillCapability capability(String skillId, SkillMetadata metadata, SkillSdkCapabilityManifest.Entry entry,
                                       ToolCall tool) {
        var parts = entry.parts();
        if (KIND_MCP.equals(entry.kind())) {
            return new SkillCapability(skillId, metadata.getQualifiedName(), KIND_MCP,
                    "mcp:" + parts.get(0) + ":" + parts.get(1),
                    parts.get(0) + "/" + parts.get(1), parts.get(0), tool);
        }
        return new SkillCapability(skillId, metadata.getQualifiedName(), KIND_API,
                String.join("/", parts), String.join("/", parts), parts.get(0), tool);
    }
}
