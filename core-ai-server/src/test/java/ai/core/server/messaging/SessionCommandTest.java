package ai.core.server.messaging;

import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCommandTest {
    @Test
    void channelImagesSurviveCommandStreamSerializationWithTheReplyTarget() {
        var attachments = List.of(Map.of("type", "IMAGE", "container", "multimodal",
                "blobName", "ai/uploads/sign.png", "contentType", "image/png", "fileName", "sign.png"));
        var command = SessionCommand.channelSendMessage("s-1", "u-1", "read this", "chan-1", "qqbot:c2c:OPENID", attachments);
        var restored = SessionCommand.fromMap(Map.of("type", command.type().name(), "sessionId", command.sessionId(),
                "userId", command.userId(), "payload", command.payload()));

        var payload = payloadOf(restored);
        assertEquals(attachments, payload.get("multimodalAttachments"));
        assertEquals("read this", payload.get("message"));
        assertEquals("chan-1", payload.get("channelId"));
        assertEquals("qqbot:c2c:OPENID", payload.get("noticeRecipient"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(SessionCommand command) {
        return JsonUtil.fromJson(Map.class, command.payload());
    }

    @Test
    void aChannelMessageCarriesWhereItCameFrom() {
        var command = SessionCommand.channelSendMessage("s-1", "u-1", "publish it", "chan-1", "qqbot:c2c:OPENID");

        assertEquals(CommandType.SEND_MESSAGE, command.type());
        var payload = payloadOf(command);
        assertEquals("publish it", payload.get("message"));
        assertEquals("chan-1", payload.get("channelId"));
        assertEquals("qqbot:c2c:OPENID", payload.get("noticeRecipient"));
    }

    @Test
    void anUnknownTargetLeavesNoRecipient() {
        var command = SessionCommand.channelSendMessage("s-1", "u-1", "hi", "chan-1", null);

        var payload = payloadOf(command);
        assertEquals("chan-1", payload.get("channelId"));
        assertFalse(payload.containsKey("noticeRecipient"), "nothing may be marked answered without an address");
    }

    @Test
    void aChatMessageCarriesNoneOfIt() {
        var payload = payloadOf(SessionCommand.sendMessage("s-1", "u-1", "hello", null));

        assertNull(payload.get("channelId"));
        assertNull(payload.get("noticeRecipient"));
        assertTrue(payload.get("variables") instanceof Map);
    }
}
