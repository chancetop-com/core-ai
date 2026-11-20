package ai.core.server.agentbuilder;

import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.agent.AgentPublishService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.migration.SchemaMigrationVDefaultAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentBuilderToolsTest {
    private final AgentDefinitionStore store = mock(AgentDefinitionStore.class);
    private final AgentPublishService publisher = mock(AgentPublishService.class);

    @Test
    void upgradesActiveStoreAndPublishesWithoutDroppingOtherBindings() {
        var definition = definition();
        definition.tools = List.of(ToolRef.of("builtin:builtin-all", ToolSourceType.BUILTIN),
                ToolRef.of("builtin:self-harness:create_agent", ToolSourceType.BUILTIN),
                ToolRef.of("custom-mcp", ToolSourceType.MCP));
        definition.model = "configured-model";
        definition.enableMemory = Boolean.FALSE;
        when(store.get("agent-builder")).thenReturn(Optional.of(definition));

        builder().initialize();

        var captor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(publisher).publish(captor.capture(), eq("system"), any());
        var upgraded = captor.getValue();
        assertEquals(SchemaMigrationVDefaultAgent.agentBuilderSystemPrompt(), upgraded.systemPrompt);
        assertEquals(SchemaMigrationVDefaultAgent.AGENT_BUILDER_DESCRIPTION, upgraded.description);
        assertEquals("20261120002", upgraded.systemSeedVersion);
        assertEquals("configured-model", upgraded.model);
        assertFalse(upgraded.enableMemory);
        var ids = upgraded.tools.stream().map(tool -> tool.id).toList();
        assertTrue(ids.contains("custom-mcp"));
        assertTrue(ids.contains("builtin:builtin-all"));
        assertTrue(ids.containsAll(SchemaMigrationVDefaultAgent.AGENT_BUILDER_TOOL_NAMES.stream()
                .map(name -> "builtin:self-harness:" + name).toList()));
        assertEquals(1L, ids.stream().filter("builtin:self-harness:create_agent"::equals).count());
    }

    @Test
    void completedUpgradeLeavesLaterAdminCustomizationsAlone() {
        var definition = definition();
        definition.systemSeedVersion = "20261120002";
        definition.systemPrompt = "Admin customization";
        when(store.get("agent-builder")).thenReturn(Optional.of(definition));

        builder().initialize();

        assertEquals("Admin customization", definition.systemPrompt);
        verify(publisher, never()).publish(any(), any(), any());
        verify(store, never()).replaceIfRevision(any(), anyInt());
    }

    @Test
    void draftUpgradeUsesCasAndDoesNotPublish() {
        var definition = definition();
        definition.status = AgentStatus.DRAFT;
        definition.definitionRevision = 7;
        when(store.get("agent-builder")).thenReturn(Optional.of(definition));
        when(store.replaceIfRevision(definition, 7)).thenReturn(Boolean.TRUE);

        builder().initialize();

        verify(store).replaceIfRevision(definition, 7);
        verify(publisher, never()).publish(any(), any(), any());
        assertEquals(AgentStatus.DRAFT, definition.status);
    }

    @Test
    void doesNotTouchMissingOrNonSystemAgents() {
        when(store.get("agent-builder")).thenReturn(Optional.empty());
        builder().initialize();
        var definition = definition();
        definition.systemDefault = Boolean.FALSE;
        when(store.get("agent-builder")).thenReturn(Optional.of(definition));
        builder().initialize();
        verify(publisher, never()).publish(any(), any(), any());
    }

    private AgentBuilderTools builder() {
        var builder = new AgentBuilderTools();
        builder.agentDefinitionStore = store;
        builder.agentPublishService = publisher;
        return builder;
    }

    private AgentDefinition definition() {
        var definition = new AgentDefinition();
        definition.id = "agent-builder";
        definition.userId = "system";
        definition.name = "Agent Builder";
        definition.systemDefault = Boolean.TRUE;
        definition.type = DefinitionType.AGENT;
        definition.status = AgentStatus.PUBLISHED;
        return definition;
    }
}
