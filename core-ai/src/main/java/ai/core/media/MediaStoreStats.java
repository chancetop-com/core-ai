package ai.core.media;

/**
 * @author core-ai
 */
public record MediaStoreStats(long bytes, int objects, long missed, long evicted) {
    public static final MediaStoreStats EMPTY = new MediaStoreStats(0, 0, 0, 0);
}
