package ai.core.media;

import java.util.Optional;

/**
 * The single owner of a session's media bytes. Conversation state holds only {@link MediaRef}s; a
 * request materializes the refs it needs and drops the bytes when the request ends.
 *
 * <p>Implementations must enforce: one authoritative copy per object, ownership/root checks on every
 * read, the per-image and per-session budgets, and a TTL that never deletes bytes still referenced by
 * an in-flight request.
 *
 * @author core-ai
 */
public interface SessionMediaStore {
    /** Custom variable carrying the store of the current execution context. */
    String CONTEXT_KEY = "__session_media_store";
    /** Custom variable carrying the {@link SessionMediaKey} of the current execution context. */
    String KEY_CONTEXT_KEY = "__session_media_key";

    /**
     * Stores bytes and returns the reference that replaces them in conversation state.
     *
     * @throws MediaLimitExceededException when a per-image or per-session budget would be exceeded
     */
    MediaRef put(SessionMediaKey key, byte[] bytes, String mediaType, String displayName, String source);

    /**
     * Registers bytes that already live in a durable backend (a server upload blob) without copying
     * them into the store again.
     */
    MediaRef register(SessionMediaKey key, MediaRef existing);

    Optional<MediaRef> find(SessionMediaKey key, String mediaId);

    /** @return the bytes, or empty when the ref is missing, expired, or not owned by {@code key} */
    Optional<byte[]> read(SessionMediaKey key, MediaRef ref);

    /** Deletes every byte owned by the session (session close / delete). */
    void deleteSession(String sessionId);

    MediaStoreStats stats();
}
