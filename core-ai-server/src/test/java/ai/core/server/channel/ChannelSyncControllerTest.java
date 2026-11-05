package ai.core.server.channel;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.PersonalAssistantStubs;
import ai.core.server.channel.openclaw.OcgCallbackPool;
import ai.core.server.channel.openclaw.OcgConfigStore;
import ai.core.server.channel.openclaw.OcgConfigView;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.SessionCommand;
import ai.core.utils.JsonUtil;
import core.framework.web.Request;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelSyncControllerTest {
    private static final String IMAGE_URL = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9d8AAAAASUVORK5CYII=";

    @Test
    void imageOnlyMessageCarriesCurrentAttachmentsWithoutReusingHistory() {
        var controller = configuredController();
        var attachment = storedImage("current.png");
        when(controller.imageAttachments.store(List.of(IMAGE_URL))).thenReturn(List.of(attachment));
        var request = requestWithMessages(List.of(
                Map.of("role", "user", "content", "Old request"),
                Map.of("role", "assistant", "content", "Old response"),
                Map.of("role", "user", "content", List.of(imagePart(IMAGE_URL)))));

        controller.execute(request);

        var payload = publishedPayload(controller);
        assertEquals("", payload.get("message"));
        assertEquals(List.of(attachment), payload.get("multimodalAttachments"));
        assertEquals("channel-a", payload.get("channelId"));
    }

    @Test
    void carriesMultipleImagesAndAllTextThroughTheChannelCommand() {
        var controller = configuredController();
        var secondUrl = IMAGE_URL.replace("image/png", "image/PNG");
        var attachments = List.of(storedImage("first.png"), storedImage("second.png"));
        when(controller.imageAttachments.store(List.of(IMAGE_URL, secondUrl))).thenReturn(attachments);
        var request = requestWithMessages(List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "First sign"), imagePart(IMAGE_URL),
                Map.of("type", "text", "text", "Second sign"), imagePart(secondUrl)))));

        controller.execute(request);

        var payload = publishedPayload(controller);
        assertEquals("First sign\nSecond sign", payload.get("message"));
        assertEquals(attachments, payload.get("multimodalAttachments"));
    }

    @Test
    void malformedImageIsRejectedInsteadOfSendingOnlyText() {
        var controller = configuredController();
        var request = requestWithMessages(List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "Read this"),
                Map.of("type", "image_url", "image_url", Map.of())))));

        assertThrows(BadRequestException.class, () -> controller.execute(request));

        verify(controller.commandPublisher, never()).publish(any());
    }

    @Test
    void attachmentImportFailureIsReturnedBeforeStartingAnAgentSession() {
        var controller = configuredController();
        when(controller.imageAttachments.store(List.of(IMAGE_URL))).thenThrow(new BadRequestException("image exceeds channel limit"));
        var request = requestWithMessages(List.of(Map.of("role", "user", "content", List.of(imagePart(IMAGE_URL)))));

        var error = assertThrows(BadRequestException.class, () -> controller.execute(request));

        assertTrue(error.getMessage().contains("image exceeds channel limit"));
        verify(controller.commandPublisher, never()).publish(any());
        verify(controller.sessionManager, never()).createSessionFromAgent(any(), any(), any(), any());
    }

    @Test
    void preservesAllTextPartsInTheLatestUserMessage() {
        var controller = configuredController();
        var request = requestWithMessages(List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "New opening hours"),
                Map.of("type", "text", "text", "Please read the attached sign")))));

        controller.execute(request);

        assertEquals("New opening hours\nPlease read the attached sign", publishedPayload(controller).get("message"));
    }

    @Test
    void anEmptyLatestUserMessageDoesNotResendEarlierText() {
        var controller = configuredController();
        var request = requestWithMessages(List.of(
                Map.of("role", "user", "content", "Old request"),
                Map.of("role", "assistant", "content", "Old response"),
                Map.of("role", "user", "content", List.of())));

        assertThrows(BadRequestException.class, () -> controller.execute(request));

        verify(controller.commandPublisher, never()).publish(any());
    }

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
        controller.personalAssistantService = PersonalAssistantStubs.passThrough();
        controller.userChannelTargetStore = mock(UserChannelTargetStore.class);

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
        // the message is carried by the command and written once, by the replica that runs the turn
        verify(controller.chatMessageService, never()).writeUserMessage(any(), any());
        verify(controller.commandPublisher, times(2)).publish(any());
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
        controller.personalAssistantService = PersonalAssistantStubs.passThrough();
        controller.userChannelTargetStore = mock(UserChannelTargetStore.class);
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
        verify(controller.chatMessageService, never()).writeUserMessage(any(), any());
        verify(controller.commandPublisher, times(2)).publish(any());
    }

    @Test
    void remembersThePeerTargetFromTheHeaderAndIgnoresTheSessionKey() {
        var controller = new ChannelSyncController();
        controller.channelConfigStore = mock(ChannelConfigStore.class);
        controller.agentDefinitionService = mock(AgentDefinitionService.class);
        controller.sessionManager = mock(AgentSessionManager.class);
        controller.chatMessageService = mock(ChatMessageService.class);
        controller.commandPublisher = mock(CommandPublisher.class);
        controller.ocgCallbackPool = mock(OcgCallbackPool.class);
        controller.ocgConfigStore = mock(OcgConfigStore.class);
        controller.personalAssistantService = PersonalAssistantStubs.passThrough();
        controller.userChannelTargetStore = mock(UserChannelTargetStore.class);
        var channel = channel("channel-a", "agent-a", "owner-a");
        var agent = agent("agent-a");
        when(controller.channelConfigStore.load("channel-a")).thenReturn(channel);
        when(controller.agentDefinitionService.getEntity("agent-a")).thenReturn(agent);
        when(controller.ocgConfigStore.loadByChannelId(any())).thenReturn(enabledOcg());
        when(controller.sessionManager.createSessionFromAgent(eq(agent), any(), eq("owner-a"), eq("channel")))
                .thenReturn(new AgentSessionManager.SessionCreationResult("session-a", List.of(), List.of(), agent));
        when(controller.chatMessageService.history(any())).thenReturn(List.of());

        controller.execute(request("channel-a", "with target", "qqbot:c2c:OPENID"));
        // a group target is not a personal address: nothing is remembered for it
        controller.execute(request("channel-a", "from a group", "qqbot:group:123456"));
        // an older gateway sends no target: nothing addressable is remembered at all, rather than
        // remembering the session key the body carries
        controller.execute(request("channel-a", "without target"));

        verify(controller.userChannelTargetStore).record("owner-a", "channel-a", "qqbot:c2c:OPENID");
        verify(controller.userChannelTargetStore, times(1)).record(any(), any(), any());
    }

    private Request requestWithMessages(List<Map<String, Object>> messages) {
        var request = request("channel-a", "unused");
        var body = JsonUtil.toJson(Map.of("user", "shared-conversation", "messages", messages));
        when(request.body()).thenReturn(Optional.of(body.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private ChannelSyncController configuredController() {
        var controller = new ChannelSyncController();
        controller.channelConfigStore = mock(ChannelConfigStore.class);
        controller.agentDefinitionService = mock(AgentDefinitionService.class);
        controller.sessionManager = mock(AgentSessionManager.class);
        controller.chatMessageService = mock(ChatMessageService.class);
        controller.commandPublisher = mock(CommandPublisher.class);
        controller.ocgCallbackPool = mock(OcgCallbackPool.class);
        controller.ocgConfigStore = mock(OcgConfigStore.class);
        controller.personalAssistantService = PersonalAssistantStubs.passThrough();
        controller.userChannelTargetStore = mock(UserChannelTargetStore.class);
        var agent = agent("agent-a");
        controller.imageAttachments = mock(ChannelImageAttachments.class);
        when(controller.channelConfigStore.load("channel-a")).thenReturn(channel("channel-a", "agent-a", "owner-a"));
        when(controller.agentDefinitionService.getEntity("agent-a")).thenReturn(agent);
        when(controller.ocgConfigStore.loadByChannelId(any())).thenReturn(enabledOcg());
        when(controller.sessionManager.createSessionFromAgent(eq(agent), any(), eq("owner-a"), eq("channel")))
                .thenReturn(new AgentSessionManager.SessionCreationResult("session-a", List.of(), List.of(), agent));
        when(controller.chatMessageService.history(any())).thenReturn(List.of());
        return controller;
    }

    private Map<String, Object> imagePart(String url) {
        return Map.of("type", "image_url", "image_url", Map.of("url", url));
    }

    private Map<String, String> storedImage(String name) {
        return Map.of("type", "IMAGE", "fileName", name, "contentType", "image/png",
                "container", "multimodal", "blobName", "ai/uploads/" + name,
                "url", "https://storage.example/" + name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishedPayload(ChannelSyncController controller) {
        var captor = ArgumentCaptor.forClass(SessionCommand.class);
        verify(controller.commandPublisher).publish(captor.capture());
        return JsonUtil.fromJson(Map.class, captor.getValue().payload());
    }

    private Request request(String channelId, String message) {
        return request(channelId, message, null);
    }

    private Request request(String channelId, String message, String peerTarget) {
        var request = mock(Request.class);
        var body = "{\"user\":\"shared-conversation\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}]}"
                .formatted(message);
        when(request.body()).thenReturn(Optional.of(body.getBytes(StandardCharsets.UTF_8)));
        when(request.pathParam("channelId")).thenReturn(channelId);
        when(request.header("X-OCG-Callback")).thenReturn(Optional.of("https://callback.example/reply"));
        when(request.header("X-OCG-Target")).thenReturn(Optional.ofNullable(peerTarget));
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
