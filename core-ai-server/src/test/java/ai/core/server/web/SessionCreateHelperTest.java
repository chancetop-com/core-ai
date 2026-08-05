package ai.core.server.web;

import ai.core.api.server.session.CreateSessionRequest;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.api.server.tool.ToolRefView;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.SessionState;
import ai.core.server.tool.ToolRegistryService;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionCreateHelperTest {
    @Test
    void createPersistsTheExecutableDefinitionReturnedBySessionBoundary() {
        var raw = new AgentDefinition();
        raw.id = "public-agent";
        raw.name = "Public Agent";
        raw.model = "edited-private-model";
        var executable = new AgentDefinition();
        executable.id = raw.id;
        executable.name = raw.name;
        executable.publishedConfig = new AgentPublishedConfig();
        executable.publishedConfig.model = "published-model";
        var helper = new SessionCreateHelper();
        helper.agentDefinitionService = mock(AgentDefinitionService.class);
        when(helper.agentDefinitionService.getEntity(raw.id)).thenReturn(raw);
        helper.sessionManager = mock(AgentSessionManager.class);
        when(helper.sessionManager.createSessionFromAgent(raw, null, "caller-1"))
            .thenReturn(new AgentSessionManager.SessionCreationResult(
                "session-1", List.of(), List.of(), executable));
        var state = new SessionState();

        helper.createSessionFromAgent(raw.id, state, "caller-1", new ArrayList<>(), new ArrayList<>());

        assertEquals("published-model", state.agentConfig.model);
        assertEquals(SessionState.CURRENT_AGENT_SNAPSHOT_SECURITY_VERSION,
                state.agentSnapshotSecurityVersion);
        assertEquals(SessionState.CURRENT_SANDBOX_BINDING_SECURITY_VERSION,
                state.sandboxBindingSecurityVersion);
    }

    @Test
    void publishedSnapshotNeverFallsBackToEditableFields() {
        var definition = new AgentDefinition();
        definition.id = "public-agent";
        definition.name = "Public Agent";
        definition.systemPrompt = "edited private prompt";
        definition.systemPromptId = "edited-private-prompt-id";
        definition.model = "edited-private-model";
        definition.inputTemplate = "edited private input";
        definition.variables = Map.of("secret", "edited-private-variable");
        definition.tools = List.of(ToolRef.of("edited-private-tool", ToolSourceType.API));
        var privateDataset = new AgentDatasetConfig();
        privateDataset.datasetId = "edited-private-dataset";
        definition.datasetConfig = List.of(privateDataset);
        definition.publishedConfig = new AgentPublishedConfig();

        var snapshot = new SessionCreateHelper().buildAgentConfigSnapshot(definition);

        assertNull(snapshot.systemPrompt);
        assertNull(snapshot.systemPromptId);
        assertNull(snapshot.model);
        assertNull(snapshot.inputTemplate);
        assertNull(snapshot.variables);
        assertNull(snapshot.tools);
        assertNull(snapshot.datasetConfig);
    }

    @Test
    void loadToolsOnSessionCreateReturnsOriginalToolRefIds() {
        var helper = new SessionCreateHelper();
        helper.sessionManager = mock(AgentSessionManager.class);
        helper.toolRegistryService = mock(ToolRegistryService.class);
        var ref = toolRef("mcp-1", ToolSourceType.MCP);
        when(helper.sessionManager.loadToolRefs(eq("s-1"), eq(List.of(ref)), eq("caller-1")))
            .thenReturn(List.of(ref));
        var registry = new ToolRegistryEntry();
        registry.id = "mcp-1";
        registry.name = "Weather";
        registry.type = ToolType.MCP;
        when(helper.toolRegistryService.getTool("mcp-1")).thenReturn(registry);

        var loaded = helper.loadToolsOnSessionCreate("s-1", request("mcp-1", "MCP"), "caller-1");

        assertEquals(1, loaded.size());
        assertEquals("mcp-1", loaded.getFirst().id);
        assertEquals("Weather", loaded.getFirst().name);
    }

    @Test
    void loadToolsOnSessionCreatePropagatesUnresolvedRefs() {
        var helper = new SessionCreateHelper();
        helper.sessionManager = mock(AgentSessionManager.class);
        var ref = toolRef("missing-mcp", ToolSourceType.MCP);
        when(helper.sessionManager.loadToolRefs(eq("s-1"), eq(List.of(ref)), eq("caller-1")))
                .thenThrow(new NotFoundException("no tools found for refs: " + List.of(ref)));

        assertThrows(NotFoundException.class,
            () -> helper.loadToolsOnSessionCreate("s-1", request("missing-mcp", "MCP"), "caller-1"));
    }

    @Test
    void loadSkillsOnSessionCreatePreservesCallerForResourceAuthorization() {
        var helper = new SessionCreateHelper();
        helper.sessionManager = mock(AgentSessionManager.class);
        var request = new CreateSessionRequest();
        request.skillIds = List.of("skill-1");
        when(helper.sessionManager.loadSkills("s-1", List.of("skill-1"), "caller-1"))
                .thenReturn(List.of("Admin/skill-1"));

        var loaded = helper.loadSkillsOnSessionCreate("s-1", request, "caller-1");

        assertEquals("skill-1", loaded.getFirst().id);
        assertEquals("Admin/skill-1", loaded.getFirst().name);
        verify(helper.sessionManager).loadSkills("s-1", List.of("skill-1"), "caller-1");
    }

    private CreateSessionRequest request(String id, String type) {
        var tool = new ToolRefView();
        tool.id = id;
        tool.type = type;
        var request = new CreateSessionRequest();
        request.tools = List.of(tool);
        return request;
    }

    private ToolRef toolRef(String id, ToolSourceType type) {
        var ref = new ToolRef();
        ref.id = id;
        ref.type = type;
        return ref;
    }
}
