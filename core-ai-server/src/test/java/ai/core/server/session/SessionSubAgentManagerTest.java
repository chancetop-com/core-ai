package ai.core.server.session;

import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.tools.SubAgentToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
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
        when(assembler.assemble(List.of("pub-1", "pub-2"), "s-1")).thenReturn(List.of(firstTool, secondTool));

        var definition = new AgentDefinition();
        definition.subAgentIds = List.of("draft-1");
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.subAgentIds = List.of("pub-1", " pub-2 ", "pub-1");

        var names = manager.loadSubAgentsFromDefinition(session, definition);

        verify(assembler).assemble(List.of("pub-1", "pub-2"), "s-1");
        verify(session, times(2)).loadTools(anyList());
        verify(chatMessageService).addLoadedSubAgentIds("s-1", List.of("pub-1", "pub-2"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of("Published One", "Published Two"), names);
    }
}
