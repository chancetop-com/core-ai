package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.SessionNotice;
import ai.core.session.InProcessAgentSession;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The notice block inside a channel session's system prompt: which notices this user has not replied
 * to, so a two-word answer ("publish") has something to point at.
 *
 * <p>A channel session is long-lived and the notice is usually sent after it was built, so the block
 * cannot be injected once at build time. Instead every inbound message refreshes it on the owning
 * replica — the same place that marks the previous notices answered — and only when the rendered text
 * actually differs: an unchanged system prompt keeps whatever prefix the model had cached.
 *
 * <p>The block is delimited by HTML comment markers rather than by the markdown heading, so replacing
 * it never depends on where the next section begins.
 *
 * @author stephen
 */
public class SessionNoticePrompt {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionNoticePrompt.class);
    static final String BLOCK_START = "<!-- notices:start -->";
    static final String BLOCK_END = "<!-- notices:end -->";
    static final String TITLE = "## Recent notices to this user (unanswered)";
    static final String FOOTER = """
            If the user's message refers to one of them — a short instruction, a decision, or something you cannot
            see in this conversation — read that session with read_session before answering instead of asking the
            user to repeat what you can look up. If it is unclear which notice they mean, ask; never guess the
            target of an action that cannot be undone.""";

    /** Appended to every channel session, next to the channel-type sentence, so the reply case is never a surprise. */
    static final String CHANNEL_RULES = """

            The user may be answering a notice the platform sent them here about work that finished in their web
            chat — a short instruction with no matching context in this conversation is often exactly that. Look the
            conversation up before acting on it, and when the target is not clear, ask rather than guess: anything
            that cannot be undone (publishing, deleting, sending, paying) needs their confirmation first.""";

    private static final Pattern BLOCK = Pattern.compile("(?s)\n*" + Pattern.quote(BLOCK_START) + ".*?" + Pattern.quote(BLOCK_END));
    private static final int MAX_TITLE_CHARS = 80;

    /** The block, or null when there is nothing to say — in which case the prompt carries no section at all. */
    static String renderBlock(List<SessionNotice> notices, ZonedDateTime now) {
        if (notices == null || notices.isEmpty()) return null;
        var text = new StringBuilder(512);
        text.append(BLOCK_START).append('\n').append(TITLE).append('\n');
        for (var notice : notices) {
            text.append("- ").append(relativeTime(notice.createdAt, now));
            var title = oneLine(notice.title);
            if (title != null) text.append(" · \"").append(title).append('"');
            text.append(" · session ").append(notice.sessionId);
            var summary = oneLine(notice.summary);
            if (summary != null) text.append(" · ").append(summary);
            text.append('\n');
        }
        text.append('\n').append(FOOTER).append('\n').append(BLOCK_END);
        return text.toString();
    }

    /** True when the prompt changed — only then is it worth breaking the model's cached prefix. */
    static boolean applyBlock(Agent agent, String block) {
        var current = agent.getSystemPrompt();
        var updated = replaceBlock(current, block);
        if (updated == null || updated.equals(current)) return false;
        agent.setSystemPrompt(updated);
        patchSystemMessage(agent.getMessages(), block);
        return true;
    }

    // the conversation may have been restored as history alone: only a leading system message is this prompt's
    private static void patchSystemMessage(List<Message> messages, String block) {
        if (messages.isEmpty() || messages.getFirst().role != RoleType.SYSTEM) return;
        var system = messages.getFirst();
        if (system.content == null || system.content.isEmpty()) return;
        var text = system.content.getFirst().text;
        if (text != null) system.content = List.of(Content.of(replaceBlock(text, block)));
    }

    // the block is model-facing text built from user data (session titles may hold '$'), which as a
    // replacement string would be read as a group reference
    static String replaceBlock(String text, String block) {
        if (text == null) return block;
        var matcher = BLOCK.matcher(text);
        if (block == null) return matcher.replaceAll("");
        if (matcher.find()) return matcher.replaceAll(Matcher.quoteReplacement(block));
        return text + "\n\n" + block;
    }

    static String relativeTime(ZonedDateTime time, ZonedDateTime now) {
        if (time == null) return "recently";
        var minutes = Math.max(0, ChronoUnit.MINUTES.between(time.toInstant(), now.toInstant()));
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        var hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return hours / 24 + "d ago";
    }

    /** One line per notice: quotes, braces and newlines from a title would break the block's shape. */
    private static String oneLine(String value) {
        if (value == null || value.isBlank()) return null;
        var cleaned = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("[{}\"<>]", "").trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() <= MAX_TITLE_CHARS ? cleaned : cleaned.substring(0, MAX_TITLE_CHARS) + "…";
    }

    /**
     * Called for every inbound channel message, before its turn starts: that message answers whatever was
     * pending, and the model gets what is left.
     */
    @Inject
    SessionNoticeStore sessionNoticeStore;

    public void answeredAndRefresh(InProcessAgentSession session, String userId, String channelId, String recipient) {
        sessionNoticeStore.markAnswered(userId, channelId, recipient);
        var agent = session != null ? session.agent() : null;
        if (agent == null) return;
        var notices = sessionNoticeStore.unanswered(userId, channelId, recipient);
        if (applyBlock(agent, renderBlock(notices, ZonedDateTime.now()))) {
            LOGGER.info("notice block refreshed, sessionId={}, channelId={}, notices={}", session.id(), channelId, notices.size());
        }
    }
}
