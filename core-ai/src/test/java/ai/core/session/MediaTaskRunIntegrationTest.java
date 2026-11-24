package ai.core.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.session.lifecycle.TaskRunEnded;
import ai.core.session.lifecycle.TaskRunObserver;
import ai.core.session.lifecycle.TaskRunSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class MediaTaskRunIntegrationTest {
    @TempDir
    Path workspace;

    @Test
    void aPendingVideoIsTrackedAndSettlesOnlyAfterTheDownloadAndContinuation() throws Exception {
        var provider = new FakeVideoProvider();
        var llm = new MockLLMProvider();
        llm.addResponse(CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "video ready"))),
                new Usage(1, 1, 2)));
        var agent = Agent.builder().llmProvider(llm).build();
        agent.getExecutionContext().setVideoMediaProvider(provider);
        agent.getExecutionContext().getCustomVariables().put("workspace", workspace.toString());
        var session = new InProcessAgentSession("s-media-task-run", agent, true, new InMemoryToolPermissionStore());
        var recorder = new Recorder();
        session.taskRuns().setObserver(recorder);
        try {
            session.taskRuns().openRoot("request-1", "turn-1");
            session.taskRuns().executionStarted("turn-1", ai.core.session.lifecycle.TaskRunCoordinator.ExecutionKind.ROOT, 0);
            var event = ToolResultEvent.of(session.id(), "call-1", "generate_video", "pending", "video-1");
            event.taskId = "video-1";
            session.dispatchEvent(event);
            session.taskRuns().executionEnded("turn-1", ai.core.session.lifecycle.TaskRunCoordinator.ExecutionKind.ROOT,
                    ai.core.session.lifecycle.TaskRunOutcome.RETURNED, null);
            // the watcher must not deliver its notification turn before the root execution ended, or the
            // root end is rejected as a stale execution and the run can never leave the pending state
            provider.release();

            assertTrue(recorder.done.await(15, TimeUnit.SECONDS), "the video watcher must finish the run");
            assertNotNull(recorder.ended);
            assertEquals("returned", recorder.ended.outcome().name().toLowerCase(Locale.ROOT));
            var video = workspace.resolve(".core-ai").resolve("media").resolve("videos").resolve("video-video-1.mp4");
            assertTrue(Files.isRegularFile(video), "the completed video must be downloaded");
        } finally {
            session.close();
        }
    }

    private static final class Recorder implements TaskRunObserver {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile TaskRunEnded ended;

        @Override
        public void onTaskRunEnded(TaskRunSnapshot snapshot, TaskRunEnded event) {
            ended = event;
            done.countDown();
        }
    }

    private static final class FakeVideoProvider implements MediaProvider {
        private final CountDownLatch released = new CountDownLatch(1);

        void release() {
            released.countDown();
        }

        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoStatusResponse getVideoStatus(String videoId) {
            try {
                released.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new VideoStatusResponse(videoId, "completed", 100, null, System.currentTimeMillis());
        }

        @Override
        public byte[] downloadVideo(String videoId) {
            return new byte[]{1, 2, 3};
        }
    }
}
