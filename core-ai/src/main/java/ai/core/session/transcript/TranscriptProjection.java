package ai.core.session.transcript;

import ai.core.context.Compression;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reconstructs transcript records from the legacy {@code .data} message list. The base projection is
 * read-only and uses synthetic negative seqs; a gap projection is materialized with real file seqs.
 *
 * @author stephen
 */
public final class TranscriptProjection {
    private static final Pattern SYSTEM_REMINDER = Pattern.compile("(?s)^(\\s*<system-reminder>.*?</system-reminder>\\s*)+$");
    private static final Pattern TASK_NOTIFICATION = Pattern.compile("(?s)^(\\s*<task-notification>.*?</task-notification>\\s*)+$");

    @FunctionalInterface
    public interface BlobSink {
        String write(long seq, String field, int index, String text);
    }

    public static List<TranscriptRecord> base(List<Message> messages, int through) {
        int end = Math.min(through, messages.size());
        if (end <= 0) return List.of();
        var records = new TranscriptProjectionBuilder(messages, 0, end, true, 0, null).build();
        for (var i = 0; i < records.size(); i++) {
            records.get(i).seq = -(records.size() - i);
        }
        return records;
    }

    public static List<TranscriptRecord> gap(List<Message> messages, int from, int to, long startSeq, BlobSink sink) {
        int begin = Math.max(0, Math.min(from, messages.size()));
        int end = Math.max(begin, Math.min(to, messages.size()));
        if (begin >= end) return List.of();
        return new TranscriptProjectionBuilder(messages, begin, end, false, startSeq, sink).build();
    }

    public static String fingerprint(List<Message> messages) {
        if (messages.isEmpty()) return null;
        var last = messages.getLast();
        var text = last.getJoinedTextContent();
        if (text == null) text = "";
        var prefix = text.length() > 256 ? text.substring(0, 256) : text;
        return sha256(last.role.name() + ":" + prefix);
    }

    public static boolean isSystemReminder(String text) {
        return text != null && SYSTEM_REMINDER.matcher(text).matches();
    }

    public static boolean isTaskNotification(String text) {
        return text != null && TASK_NOTIFICATION.matcher(text).matches();
    }

    public static boolean isCompressionTool(String name) {
        return Compression.COMPRESSION_TOOL_NAME.equals(name);
    }

    public static String joinedText(Message message) {
        return message.getJoinedTextContent();
    }

    public static boolean hasNonText(Message message) {
        if (message.content == null) return false;
        return message.content.stream().anyMatch(part -> part.type != Content.ContentType.TEXT);
    }

    static String turnHash(int index, String text) {
        var prefix = text == null ? "" : text.strip();
        if (prefix.length() > 64) prefix = prefix.substring(0, 64);
        return "p" + Integer.toHexString((index + ":" + prefix).hashCode());
    }

    static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean isToolResultFor(Message message, String toolName) {
        return message.role == RoleType.TOOL && toolName != null && toolName.equals(message.name);
    }

    private TranscriptProjection() {
    }
}
