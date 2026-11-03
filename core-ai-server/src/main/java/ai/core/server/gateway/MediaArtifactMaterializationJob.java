package ai.core.server.gateway;

import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.MediaJob;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls unfinished video jobs and archives completed videos that no request has downloaded yet.
 * It is the safety net behind the on-demand and download-time materialization paths.
 *
 * @author Stephen
 */
public class MediaArtifactMaterializationJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaArtifactMaterializationJob.class);
    private static final int POLL_BATCH_SIZE = 20;
    private static final int MATERIALIZE_BATCH_SIZE = 3;
    private static final int POSTER_BATCH_SIZE = 2;

    @Inject
    MediaJobService mediaJobService;

    @Inject
    MediaJobArtifactStore artifactStore;

    @Inject
    MediaArtifactService mediaArtifactService;

    @Inject
    GatewayMediaProvider mediaProvider;

    @Inject
    MediaPosterService posterService;

    @Override
    public void execute(JobContext context) {
        artifactStore.releaseStaleClaims();
        pollUnfinishedVideos();
        materializeCompletedVideos();
        backfillPosters();
    }

    private void pollUnfinishedVideos() {
        for (var job : artifactStore.staleVideoJobs(POLL_BATCH_SIZE)) {
            try {
                mediaProvider.getVideoStatus(GatewayVideoHandle.encode(job.id));
                var refreshed = mediaJobService.get(job.id);
                if ("completed".equals(refreshed.state) && refreshed.fileId == null) {
                    mediaArtifactService.ensureStored(refreshed);
                }
            } catch (MediaArtifactNotReadyException e) {
                LOGGER.debug("video artifact is being archived by another request, job={}", job.id);
            } catch (RuntimeException e) {
                if (MediaArtifactService.isUnavailableError(e)) {
                    mediaJobService.updateVideoStatus(job, new VideoStatusResponse(job.upstreamVideoId, "failed", null,
                            e.getMessage(), null, null, null, null));
                }
                LOGGER.warn("video status poll failed, job={}", job.id, e);
            }
        }
    }

    private void materializeCompletedVideos() {
        for (MediaJob job : artifactStore.backfillCandidates(MATERIALIZE_BATCH_SIZE)) {
            try {
                mediaArtifactService.ensureStored(job);
            } catch (MediaArtifactNotReadyException e) {
                LOGGER.debug("video artifact is being archived by another request, job={}", job.id);
            } catch (MediaArtifactUnavailableException e) {
                LOGGER.info("video artifact expired upstream, job={}, error={}", job.id, e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.warn("video artifact materialization failed, job={}", job.id, e);
            }
        }
    }

    private void backfillPosters() {
        for (var job : artifactStore.posterCandidates(POSTER_BATCH_SIZE)) {
            posterService.generateFromStored(job);
        }
    }
}
