package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.server.domain.SessionNotice;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionNoticePromptTest {
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-09-28T08:00:00Z");

    @Test
    void noUnansweredNoticeMeansNoSectionAtAll() {
        assertNull(SessionNoticePrompt.renderBlock(List.of(), NOW));
        assertNull(SessionNoticePrompt.renderBlock(null, NOW));
    }

    @Test
    void blockNamesEveryNoticeAndHowToOpenIt() {
        var block = SessionNoticePrompt.renderBlock(List.of(notice(NOW.minusMinutes(12), "Nightly report")), NOW);

        assertTrue(block.startsWith(SessionNoticePrompt.BLOCK_START), block);
        assertTrue(block.endsWith(SessionNoticePrompt.BLOCK_END), block);
        assertTrue(block.contains(SessionNoticePrompt.TITLE));
        assertTrue(block.contains("12m ago"));
        assertTrue(block.contains("\"Nightly report\""));
        assertTrue(block.contains("session s-1"));
        assertTrue(block.contains("session finished after 6m 00s"));
        assertTrue(block.contains("read_session"), "the model must be told which tool opens the conversation");
    }

    @Test
    void relativeTimeDegradesFromMinutesToDays() {
        assertEquals("just now", SessionNoticePrompt.relativeTime(NOW.minusSeconds(20), NOW));
        assertEquals("12m ago", SessionNoticePrompt.relativeTime(NOW.minusMinutes(12), NOW));
        assertEquals("5h ago", SessionNoticePrompt.relativeTime(NOW.minusHours(5), NOW));
        assertEquals("3d ago", SessionNoticePrompt.relativeTime(NOW.minusDays(3), NOW));
        assertEquals("recently", SessionNoticePrompt.relativeTime(null, NOW));
    }

    @Test
    void aTitleCannotBreakTheBlockItsShape() {
        var title = "first\nsecond {{var}} <b>quote \" end";
        var block = SessionNoticePrompt.renderBlock(List.of(noticeWithTitle(title)), NOW);

        assertFalse(block.contains("{{var}}"), block);
        assertFalse(block.contains("<b>"), block);
        assertFalse(block.contains("first\nsecond"), block);
        assertTrue(block.contains("first second"), block);
    }

    @Test
    void replacementTreatsUserDataLiterallyAndNeverStacksBlocks() {
        var block = SessionNoticePrompt.renderBlock(List.of(noticeWithTitle("Path $merge and $set")), NOW);

        var withBlock = SessionNoticePrompt.replaceBlock("base prompt", block);
        assertTrue(withBlock.startsWith("base prompt\n\n"), withBlock);

        var replaced = SessionNoticePrompt.replaceBlock(withBlock, block);
        assertEquals(1, occurrences(replaced, SessionNoticePrompt.BLOCK_START), replaced);

        assertEquals("base prompt", SessionNoticePrompt.replaceBlock(withBlock, null), "an answered notice leaves no section");
    }

    @Test
    void anUnchangedPromptIsLeftAlone() {
        var agent = mock(Agent.class);
        when(agent.getSystemPrompt()).thenReturn("prompt without notices");
        when(agent.getMessages()).thenReturn(List.of());

        assertFalse(SessionNoticePrompt.applyBlock(agent, null));
        verify(agent, never()).setSystemPrompt(anyString());

        assertTrue(SessionNoticePrompt.applyBlock(agent, "<!-- notices:start -->\n## x\n<!-- notices:end -->"));
        verify(agent).setSystemPrompt(anyString());
    }

    private SessionNotice notice(ZonedDateTime createdAt, String title) {
        var notice = new SessionNotice();
        notice.userId = "u-1";
        notice.channelId = "chan-1";
        notice.recipient = "qqbot:c2c:OPENID";
        notice.kind = SessionNotice.KIND_SESSION_COMPLETION;
        notice.sessionId = "s-1";
        notice.title = title;
        notice.summary = "session finished after 6m 00s";
        notice.createdAt = createdAt;
        return notice;
    }

    private SessionNotice noticeWithTitle(String title) {
        return notice(NOW, title);
    }

    private int occurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }
}
