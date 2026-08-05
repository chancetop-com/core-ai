package ai.core.server.channel;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.channel.openclaw.OcgCallbackPool;
import ai.core.server.channel.openclaw.OcgConfigStore;
import ai.core.server.channel.openclaw.OcgConfigView;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.messaging.CommandPublisher;
import core.framework.web.Request;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelSyncControllerTest {
    @Test
    void sameConversationIdOnDifferentChannelsDoesNotReuseAnotherChannelsSession() {
        var controller = new ChannelSyncController();
        controller.channelConfigStore = mock(ChannelConfigStore.class);
        controller.agentDefinitionService = mock(AgentDefinitionService.class);
        controller.sessionManager = mock(AgentSessionManager.class);
        controller.chatMessageService = mock(ChatMessageService.class);
        controller.commandPublisher = mock(CommandPublisher.class);
        controller.ocgCallbackPool = mock(OcgCallbackPool.class);
        controller.ocgConfigStore = mock(OcgConfigStore.class);

        var channelA = channel("channel-a", "agent-a", "owner-a");
        var channelB = channel("channel-b", "agent-b", "owner-b");
        var agentA = agent("agent-a");
        var agentB = agent("agent-b");
        when(controller.channelConfigStore.load("channel-a")).thenReturn(channelA);
        when(controller.channelConfigStore.load("channel-b")).thenReturn(channelB);
        when(controller.agentDefinitionService.getEntity("agent-a")).thenReturn(agentA);
        when(controller.agentDefinitionService.getEntity("agent-b")).thenReturn(agentB);
        when(controller.ocgConfigStore.loadByChannelId(any())).thenReturn(enabledOcg());
        when(controller.sessionManager.createSessionFromAgent(eq(agentA), any(), eq("owner-a"), eq("channel")))
                .thenReturn(new AgentSessionManager.SessionCreationResult("session-a", List.of(), List.of(), agentA));
        when(controller.sessionManager.createSessionFromAgent(eq(agentB), any(), eq("owner-b"), eq("channel")))
                .thenReturn(new AgentSessionManager.SessionCreationResult("session-b", List.of(), List.of(), agentB));
        when(controller.sessionManager.getSession("session-a")).thenReturn(mock());
        when(controller.chatMessageService.history(any())).thenReturn(List.of());

        controller.execute(request("channel-a", "first"));
        controller.execute(request("channel-b", "second"));

        verify(controller.sessionManager).createSessionFromAgent(eq(agentA), any(), eq("owner-a"), eq("channel"));
        verify(controller.sessionManager).createSessionFromAgent(eq(agentB), any(), eq("owner-b"), eq("channel"));
        verify(controller.chatMessageService).writeUserMessage("session-a", "first");
        verify(controller.chatMessageService).writeUserMessage("session-b", "second");
        verify(controller.sessionManager, never()).getSession("session-a");
    }

    @Test
    void anonymousChannelReusesSameConversationWithStableDerivedOwner() {
        var controller = new ChannelSyncController();
        controller.channelConfigStore = mock(ChannelConfigStore.class);
        controller.agentDefinitionService = mock(AgentDefinitionService.class);
        controller.sessionManager = mock(AgentSessionManager.class);
        controller.chatMessageService = mock(ChatMessageService.class);
        controller.commandPublisher = mock(CommandPublisher.class);
        controller.ocgCallbackPool = mock(OcgCallbackPool.class);
        controller.ocgConfigStore = mock(OcgConfigStore.class);
        var channel = channel("channel-a", "agent-a", null);
        var agent = agent("agent-a");
        when(controller.channelConfigStore.load("channel-a")).thenReturn(channel);
        when(controller.agentDefinitionService.getEntity("agent-a")).thenReturn(agent);
        when(controller.ocgConfigStore.loadByChannelId("channel-a")).thenReturn(enabledOcg());
        when(controller.sessionManager.createSessionFromAgent(eq(agent), any(),
                eq("channel:channel-a:shared-conversation"), eq("channel")))
                .thenReturn(new AgentSessionManager.SessionCreationResult("session-a", List.of(), List.of(), agent));
        when(controller.sessionManager.getSession("session-a")).thenReturn(mock());
        when(controller.chatMessageService.history(any())).thenReturn(List.of());

        controller.execute(request("channel-a", "first"));
        controller.execute(request("channel-a", "second"));

        verify(controller.sessionManager, times(1)).createSessionFromAgent(eq(agent), any(),
                eq("channel:channel-a:shared-conversation"), eq("channel"));
        verify(controller.sessionManager).getSession("session-a");
        verify(controller.chatMessageService).writeUserMessage("session-a", "first");
        verify(controller.chatMessageService).writeUserMessage("session-a", "second");
    }

    private Request request(String channelId, String message) {
        var request = mock(Request.class);
        var body = "{\"user\":\"shared-conversation\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}"
            .formatted(message);
        when(request.body()).thenReturn(Optional.of(body.getBytes(StandardCharsets.UTF_8)));
        when(request.pathParam("channelId")).thenReturn(channelId);
        when(request.header("X-OCG-Callback")).thenReturn(Optional.of("https://callback.example/reply"));
        return request;
    }

    private ChannelConfigView channel(String channelId, String agentId, String userId) {
        var channel = new ChannelConfigView();
        channel.channelId = channelId;
        channel.channelType = "openclaw";
        channel.agentId = agentId;
        channel.userId = userId;
        return channel;
    }

    private AgentDefinition agent(String id) {
        var agent = new AgentDefinition();
        agent.id = id;
        return agent;
    }

    private OcgConfigView enabledOcg() {
        var config = new OcgConfigView();
        config.enabled = Boolean.TRUE;
        return config;
    }
}
