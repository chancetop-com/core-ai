package ai.core.server.domain;

/**
 * @author stephen
 */
public interface GeminiFileRepository {
    GeminiFile findBySource(String userId, String providerId, String upstreamModel,
                            String container, String blobName, String sourceETag);

    void insert(GeminiFile file);

    boolean insertIfAbsent(GeminiFile file);

    void replace(GeminiFile file);
}
