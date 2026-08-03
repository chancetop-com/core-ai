package ai.core.llm.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Xander
 */
class MessageTextContentTest {
    @Test
    void getTextContentSkipsLeadingNonTextParts() {
        var message = Message.of(new Message.MessageRecord(RoleType.USER,
                List.of(Content.of(Content.ImageUrl.of("https://blob/img.png", null)), Content.of("hello")),
                null, null, null, null));

        assertEquals("hello", message.getTextContent());
    }

    @Test
    void getJoinedTextContentJoinsAllTextPartsSkippingImages() {
        var message = Message.of(new Message.MessageRecord(RoleType.USER,
                List.of(Content.of("first"), Content.of(Content.ImageUrl.of("https://blob/img.png", null)), Content.of("second")),
                null, null, null, null));

        assertEquals("first\nsecond", message.getJoinedTextContent());
    }

    @Test
    void getTextContentStillReturnsFirstTextForPlainMessage() {
        assertEquals("plain", Message.of(RoleType.USER, "plain").getTextContent());
    }
}
