package ai.core.server.session;

import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.tools.SubAgentToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionSubAgentManagerTest {
    @Test
    void loadSubAgentsFromDefinitionUsesPublishedIdsAndPersistsThem() {
        var chatMessageService = mock(ChatMessageService.class);
        var assembler = mock(SubAgentAssembler.class);
        var manager = new SessionSubAgentManager(chatMessageService, assembler);

        var session = mock(InProcessAgentSession.class);
        when(session.id()).thenReturn("s-1");

        var firstTool = mock(SubAgentToolCall.class);
        when(firstTool.getName()).thenReturn("Published One");
        var secondTool = mock(SubAgentToolCall.class);
        when(secondTool.getName()).thenReturn("Published Two");
        when(assembler.assemble(List.of("pub-1", "pub-2"), "s-1", "caller-1"))
            .thenReturn(List.of(firstTool, secondTool));

        var definition = new AgentDefinition();
        definition.subAgentIds = List.of("draft-1");
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.subAgentIds = List.of("pub-1", " pub-2 ", "pub-1");

        var names = manager.loadSubAgentsFromDefinition(session, definition, "caller-1");

        verify(assembler).assemble(List.of("pub-1", "pub-2"), "s-1", "caller-1");
        verify(session, times(2)).loadTools(anyList());
        verify(chatMessageService).addLoadedSubAgentIds("s-1", List.of("pub-1", "pub-2"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of("Published One", "Published Two"), names);
    }

    @Test
    void topLevelToolRegistryResolutionThreadsSessionCaller() {
        var assembler = mock(SubAgentAssembler.class);
        var manager = new SessionSubAgentManager(mock(ChatMessageService.class), assembler);
        var definition = new AgentDefinition();
        var registry = mock(ToolRegistry.class);
        when(assembler.resolveTopLevelToolsToRegistry(definition, "s-1", "caller-1")).thenReturn(registry);

        ToolRegistry actual = manager.resolveTopLevelToolsToRegistry(definition, "s-1", "caller-1");

        assertSame(registry, actual);
        verify(assembler).resolveTopLevelToolsToRegistry(definition, "s-1", "caller-1");
    }

    @Test
    void dynamicallyLoadedSubAgentThreadsSessionCaller() {
        var assembler = mock(SubAgentAssembler.class);
        var manager = new SessionSubAgentManager(mock(ChatMessageService.class), assembler);
        var session = mock(InProcessAgentSession.class);
        when(session.id()).thenReturn("s-1");
        var definition = new AgentDefinition();
        definition.id = "sub-1";
        definition.name = "Sub One";
        var subAgent = mock(ai.core.agent.Agent.class);
        when(subAgent.getName()).thenReturn("Sub One");
        when(subAgent.getDescription()).thenReturn("A sub-agent");
        when(assembler.buildSubAgent(definition, "s-1", "caller-1")).thenReturn(subAgent);

        manager.loadSubAgents(session, List.of(definition), "caller-1");

        verify(assembler).buildSubAgent(definition, "s-1", "caller-1");
    }
}
