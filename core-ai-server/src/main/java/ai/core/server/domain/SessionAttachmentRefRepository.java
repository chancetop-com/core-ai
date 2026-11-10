package ai.core.server.domain;

import java.util.List;

/**
 * @author stephen
 */
public interface SessionAttachmentRefRepository {
    void insert(SessionAttachmentRef reference);

    boolean insertIfAbsent(SessionAttachmentRef reference);

    SessionAttachmentRef findOwned(String referenceId, String sessionId, String userId);

    /** Owned references whose id starts with {@code prefix}. */
    List<SessionAttachmentRef> findOwnedByPrefix(String prefix, String sessionId, String userId);

    List<SessionAttachmentRef> findOwnedVideos(String sessionId, String userId);

    void attachFile(String referenceId, String fileId);

    SessionAttachmentRef findBySource(String sessionId, String userId, String container, String blobName);

    List<SessionAttachmentRef> findSandboxAttachments(String sessionId, String userId);
}
