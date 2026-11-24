package ai.core.media;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Moves legacy inline images already sitting in a conversation into the session media store, once,
 * on the first model call after the store becomes available. The migration mutates the message in
 * place so the history projection that shares the same object stops pinning the bytes too.
 *
 * @author core-ai
 */
public final class LegacyImageMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyImageMigration.class);

    /**
     * @return true when at least one inline payload was replaced by a ref
     */
    public static boolean migrate(List<Message> messages, SessionMediaStore store, SessionMediaKey key) {
        if (store == null || key == null || messages == null || messages.isEmpty()) return false;
        var migrated = false;
        for (var message : messages) {
            if (message.content == null || message.content.isEmpty()) continue;
            List<Content> replaced = null;
            for (var i = 0; i < message.content.size(); i++) {
                var part = message.content.get(i);
                if (part == null || part.type != Content.ContentType.IMAGE_URL) continue;
                var url = part.imageUrl == null ? null : part.imageUrl.url;
                if (url == null || !url.startsWith("data:")) continue;
                if (decodedBytes(url) < MediaStoreLimits.MIN_REF_BYTES) continue;
                try {
                    var ref = store.put(key, decode(url), mediaType(part), null, "legacy inline image");
                    if (replaced == null) replaced = new ArrayList<>(message.content);
                    replaced.set(i, Content.of(ref));
                    migrated = true;
                } catch (RuntimeException e) {
                    LOGGER.warn("failed to migrate a legacy inline image to the session media store, keeping it inline, error={}", e.getMessage());
                }
            }
            if (replaced != null) message.content = List.copyOf(replaced);
        }
        return migrated;
    }

    private static byte[] decode(String dataUri) {
        var comma = dataUri.indexOf(',');
        if (comma < 0) throw new IllegalArgumentException("invalid data uri");
        return Base64.getDecoder().decode(dataUri.substring(comma + 1));
    }

    private static String mediaType(Content part) {
        if (part.imageUrl != null && part.imageUrl.format != null && !part.imageUrl.format.isBlank()) {
            return part.imageUrl.format;
        }
        var header = part.imageUrl == null ? null : part.imageUrl.url;
        if (header != null && header.startsWith("data:")) {
            var end = header.indexOf(';');
            if (end > 5) return header.substring(5, end);
        }
        return "image/png";
    }

    private static long decodedBytes(String dataUri) {
        var comma = dataUri.indexOf(',');
        if (comma < 0) return 0;
        return (dataUri.length() - comma - 1L) / 4 * 3;
    }

    private LegacyImageMigration() {
    }
}
