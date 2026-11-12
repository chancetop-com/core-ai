package ai.core.server.gateway;

import ai.core.server.domain.MediaJob;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface MediaJobStore {
    Optional<MediaJob> get(String id);

    void insert(MediaJob job);

    void replace(MediaJob job);

    Optional<MediaJob> findLatestCompleted(String sessionId, String userId, String mediaType);

    MediaJobPage list(String mediaType, String costSource, String userId, int offset, int limit);

    boolean claimArtifact(String jobId);

    void releaseStaleClaims();

    List<MediaJob> posterCandidates(int limit);

    List<MediaJob> backfillCandidates(int limit);

    List<MediaJob> staleVideoJobs(int limit);

    record MediaJobPage(long total, List<MediaJob> jobs) {
    }
}
