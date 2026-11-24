package ai.core.media;

import java.time.Duration;

/**
 * Default budgets for a session media store. They match the tech design proposal (D6): one request
 * may still send 16 images / 12 MB (the pruner's budget), one image may be 16 MB decoded, one session
 * may own 256 MB, and bytes expire 24 hours after they were written.
 *
 * @author core-ai
 */
public final class MediaStoreLimits {
    public static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024;
    public static final long MAX_SESSION_BYTES = 256L * 1024 * 1024;
    public static final Duration TTL = Duration.ofHours(24);
    /** Minimum inline size worth a ref; smaller payloads stay inline in legacy paths. */
    public static final long MIN_REF_BYTES = 32L * 1024;

    private MediaStoreLimits() {
    }
}
