package ai.core.server.session;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelOutboundAdapter;
import ai.core.server.channel.ChannelMessage;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.User;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.function.LongSupplier;

/**
 * Tells a chat session's owner on their own channel when a turn that ran long enough ends, so a
 * session started in the web chat can be walked away from. The trigger is the turn's own terminal
 * event — the same listener chain the turn-state registry and message persistence ride — because
 * "the work stopped" is an in-process fact, not something a sweep or the model should decide.
 *
 * <p>The notification is opt-in twice: the session carries the per-chat switch and the user carries
 * the target (channel + recipient) and the minimum duration. Missing configuration means silence.
 *
 * @author stephen
 */
public class SessionCompletionNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionCompletionNotifier.class);
    static final int DEFAULT_MIN_MINUTES = 5;
    // Nothing shorter than this is ever worth a message, whatever the user configured — the gate
    // exists so an ordinary quick turn costs no database reads at all.
    static final long FLOOR_MS = Duration.ofSeconds(60).toMillis();
    private static final String CHAT_SOURCE = "chat";
    private static final int MAX_DETAIL_LENGTH = 200;

    /** A user is reachable when the channel and the platform-side recipient are both set. */
    public static boolean hasTarget(User user) {
        if (user == null) return false;
        return isSet(user.notifyChannelId) && isSet(user.notifyRecipient);
    }

    static int minMinutes(User user) {
        return user != null && user.notifyMinMinutes != null && user.notifyMinMinutes > 0
                ? user.notifyMinMinutes : DEFAULT_MIN_MINUTES;
    }

    static String truncate(String text) {
        if (text == null) return "";
        return text.length() <= MAX_DETAIL_LENGTH ? text : text.substring(0, MAX_DETAIL_LENGTH) + "...";
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    @Inject
    SessionRegistry sessionRegistry;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    ChannelConfigStore channelConfigStore;
    @Inject
    ChannelRegistry channelRegistry;
    @Inject
    PublicUrlConfiguration publicUrlConfiguration;

    LongSupplier clock = System::currentTimeMillis;

    public AgentEventListener listener(String sessionId) {
        return new Listener(sessionId);
    }

    void onTurnEnded(String sessionId, TurnReport report) {
        if (report.durationMs() < FLOOR_MS) return;
        var session = sessionRegistry.get(sessionId);
        if (session == null || session.deletedAt != null) return;
        // Only the web chat entry asks for this; channel/API/scheduled sessions already report back.
        if (session.source != null && !CHAT_SOURCE.equals(session.source)) return;
        if (!Boolean.TRUE.equals(session.notifyOnComplete)) return;
        var user = userCollection.get(session.userId).orElse(null);
        if (!hasTarget(user)) return;
        if (report.durationMs() < minMinutes(user) * 60_000L) return;
        send(session, user, report);
    }

    private void send(ChatSession session, User user, TurnReport report) {
        var channel = channelConfigStore.load(user.notifyChannelId);
        if (channel == null) {
            LOGGER.warn("session completion notification skipped, channel not found, sessionId={}, channelId={}",
                    session.id, user.notifyChannelId);
            return;
        }
        if (!Boolean.TRUE.equals(channel.enabled)) {
            LOGGER.warn("session completion notification skipped, channel disabled, sessionId={}, channelId={}",
                    session.id, user.notifyChannelId);
            return;
        }
        ChannelOutboundAdapter outbound;
        try {
            outbound = channelRegistry.outbound(channel.channelType);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("session completion notification skipped, no outbound adapter, channelId={}, type={}",
                    user.notifyChannelId, channel.channelType);
            return;
        }
        var text = buildText(session, report);
        try {
            outbound.sendMessage(ChannelMessage.text(text), channel.channelId,
                    user.notifyRecipient, user.notifyRecipient, null, channel.config);
            LOGGER.info("session completion notification sent, sessionId={}, channelId={}, durationMs={}",
                    session.id, channel.channelId, report.durationMs());
        } catch (RuntimeException e) {
            LOGGER.warn("session completion notification failed, sessionId={}, channelId={}",
                    session.id, channel.channelId, e);
        }
    }

    String buildText(ChatSession session, TurnReport report) {
        var lines = new ArrayList<String>(4);
        lines.add((report.failed() ? "⚠️" : "⏱") + " Session finished — " + displayTitle(session));
        lines.add("Duration: " + formatDuration(report.durationMs()) + " · " + report.status());
        var usage = formatUsage(report);
        if (usage != null) lines.add(usage);
        var link = sessionLink(session.id);
        if (link != null) lines.add(link);
        return String.join("\n", lines);
    }

    private String formatUsage(TurnReport report) {
        var parts = new ArrayList<String>(2);
        if (report.costUsd() != null && report.costUsd() > 0) {
            parts.add("Cost: $" + Math.round(report.costUsd() * 100) / 100.0);
        }
        if (report.inputTokens() != null || report.outputTokens() != null) {
            parts.add("tokens " + compactTokens(report.inputTokens()) + " in / " + compactTokens(report.outputTokens()) + " out");
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private String sessionLink(String sessionId) {
        var base = publicUrlConfiguration.value();
        if (base == null || base.isBlank()) return null;
        return base + "/chat?sessionId=" + sessionId;
    }

    private String displayTitle(ChatSession session) {
        if (session.title != null && !session.title.isBlank()) return session.title;
        return "session " + (session.id != null && session.id.length() > 8 ? session.id.substring(0, 8) : session.id);
    }

    private String formatDuration(long durationMs) {
        var totalSeconds = Math.max(1, durationMs / 1000);
        var hours = totalSeconds / 3600;
        var minutes = totalSeconds % 3600 / 60;
        var seconds = totalSeconds % 60;
        if (hours > 0) return hours + "h " + twoDigits(minutes) + "m";
        if (minutes > 0) return minutes + "m " + twoDigits(seconds) + "s";
        return seconds + "s";
    }

    private String twoDigits(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }

    private String compactTokens(Long tokens) {
        if (tokens == null) return "0";
        if (tokens >= 1_000_000) return compact(tokens, 1_000_000) + "M";
        if (tokens >= 1_000) return compact(tokens, 1_000) + "K";
        return Long.toString(tokens);
    }

    /** One decimal place, trailing ".0" dropped: 1234567 → 1.2M, 3000 → 3K. */
    private String compact(long tokens, long scale) {
        var tenths = Math.round((double) tokens / scale * 10);
        if (tenths % 10 == 0) return Long.toString(tenths / 10);
        return tenths / 10 + "." + tenths % 10;
    }

    /** What a finished turn is worth reporting: how long it ran, how it ended, what it cost. */
    record TurnReport(long durationMs, String status, Long inputTokens, Long outputTokens, Double costUsd) {
        boolean failed() {
            return status.startsWith("failed");
        }
    }

    /**
     * Per-session listener. The turn's start comes from the RUNNING status of this same chain, so the
     * duration is measured where the turn actually runs; a session rebuilt onto another replica gets a
     * fresh listener, and a turn already in flight there simply has no start to measure and is skipped.
     */
    private final class Listener implements AgentEventListener {
        private final String sessionId;
        private volatile long turnStartedAt;

        private Listener(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onStatusChange(StatusChangeEvent event) {
            if (event.status == SessionStatus.RUNNING) {
                turnStartedAt = clock.getAsLong();
            } else {
                turnStartedAt = 0;
            }
        }

        @Override
        public void onTurnComplete(TurnCompleteEvent event) {
            if (Boolean.TRUE.equals(event.cancelled)) { // the user stopped it — they know
                turnStartedAt = 0;
                return;
            }
            var durationMs = consumeDuration();
            if (durationMs < 0) return;
            var status = Boolean.TRUE.equals(event.maxTurnsReached) ? "stopped at max turns" : "completed";
            onTurnEnded(sessionId, new TurnReport(durationMs, status, event.inputTokens, event.outputTokens, event.costUsd));
        }

        @Override
        public void onError(ErrorEvent event) {
            var durationMs = consumeDuration();
            if (durationMs < 0) return;
            var message = event.message == null || event.message.isBlank() ? "turn failed" : event.message;
            onTurnEnded(sessionId, new TurnReport(durationMs, "failed — " + truncate(message), null, null, null));
        }

        private long consumeDuration() {
            var startedAt = turnStartedAt;
            turnStartedAt = 0;
            return startedAt == 0 ? -1 : clock.getAsLong() - startedAt;
        }
    }
}
