package ai.core.server.skill;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.tool.ToolRegistryService;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillSdkCapabilityManifest;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillSdkCapabilityResolverTest {
    private static ToolCall tool(String name) {
        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed(arguments);
            }
        };
        tool.setName(name);
        tool.setDescription(name);
        tool.setParameters(new ArrayList<>());
        return tool;
    }

    private static SkillMetadata skill(Map<String, String> metadata, String... allowedTools) {
        return SkillMetadata.builder("analytics", "Analytics", "Admin/analytics")
                .namespace("Admin")
                .metadata(metadata)
                .allowedTools(List.of(allowedTools))
                .build();
    }

    @Test
    void resolvesMcpAndApiButNotUnwiredLlmCall() {
        var registry = mock(ToolRegistryService.class);
        var permissions = mock(PermissionService.class);
        when(permissions.has("user-1", PermissionCodes.MCP_CALL)).thenReturn(true);
        when(permissions.has("user-1", PermissionCodes.APITOOL_CALL)).thenReturn(true);
        when(registry.resolveToolRefs(anyList(), eq("session-1"), eq("user-1")))
                .thenReturn(List.of(tool("run_query")));

        var resolver = new SkillSdkCapabilityResolver(registry, permissions);
        var capabilities = resolver.resolve("session-1", "user-1", Map.of("skill-1", skill(
                Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1"),
                "mcp:analytics/run_query", "api-tool:crm/leads/list", "llm-call:lead-summary")));

        assertEquals(2, capabilities.size());
        assertEquals(List.of("mcp", "api"), capabilities.stream().map(cap -> cap.kind()).toList());
        assertEquals("mcp:analytics:run_query", capabilities.getFirst().refId());
        assertEquals("analytics/run_query", capabilities.getFirst().path());
        assertEquals("skill-1", capabilities.getFirst().skillId());
    }

    @Test
    void apiWhitelistRejectsOnlyThatCapability() {
        var registry = mock(ToolRegistryService.class);
        var permissions = mock(PermissionService.class);
        when(permissions.has("user-1", PermissionCodes.MCP_CALL)).thenReturn(true);
        when(permissions.has("user-1", PermissionCodes.APITOOL_CALL)).thenReturn(true);
        when(permissions.isApiUser("user-1")).thenReturn(true);
        when(permissions.checkResource("user-1", PermissionService.RESOURCE_TYPE_API_APP, "crm")).thenReturn(false);
        when(permissions.checkResource("user-1", PermissionService.RESOURCE_TYPE_MCP_SERVER, "analytics")).thenReturn(true);
        when(registry.resolveToolRefs(anyList(), eq("session-1"), eq("user-1")))
                .thenReturn(List.of(tool("run_query")));

        var capabilities = new SkillSdkCapabilityResolver(registry, permissions)
                .resolve("session-1", "user-1", Map.of("skill-1", skill(
                        Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1"),
                        "mcp:analytics/run_query", "api-tool:crm/leads/list")));

        assertEquals(1, capabilities.size());
        assertEquals("mcp", capabilities.getFirst().kind());
    }

    @Test
    void skillsWithoutOptInContributeNothing() {
        var registry = mock(ToolRegistryService.class);
        var capabilities = new SkillSdkCapabilityResolver(registry, mock(PermissionService.class))
                .resolve("session-1", "user-1", Map.of("skill-1", skill(Map.of(), "mcp:analytics/run_query")));

        assertEquals(List.of(), capabilities);
    }
}
