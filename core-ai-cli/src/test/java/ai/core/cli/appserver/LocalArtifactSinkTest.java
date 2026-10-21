package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.CustomEvent;
import ai.core.session.CustomEventEmitter;
import ai.core.session.SessionCustomEventEmitter;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LocalArtifactSinkTest {
    @TempDir
    Path workspace;

    private final List<AgentEvent> dispatched = new ArrayList<>();

    private ExecutionContext context(boolean turnActive, boolean withEmitter) {
        var builder = ExecutionContext.builder().sessionId("s-1");
        if (withEmitter) {
            builder.customVariable(CustomEventEmitter.CONTEXT_KEY,
                    new SessionCustomEventEmitter("s-1", dispatched::add, () -> turnActive));
        }
        var context = builder.build();
        context.setCurrentToolCallId("call_1");
        return context;
    }

    @Test
    void writesImageAndPublishesEvent() throws Exception {
        var bytes = new byte[]{1, 2, 3, 4};
        var sink = new LocalArtifactSink(workspace, context(true, true));

        var returned = sink.save("image-x.png", "image/png", bytes);

        var target = workspace.resolve(".core-ai/media/images/image-x.png");
        assertEquals(target, Path.of(returned));
        assertTrue(Files.exists(target));
        assertEquals(4, Files.readAllBytes(target).length);
        assertEquals(List.of(new LocalArtifactManifest.Entry(".core-ai/media/images/image-x.png", "s-1")),
                LocalArtifactManifest.entries(workspace));

        assertEquals(1, dispatched.size());
        var event = (CustomEvent) dispatched.getFirst();
        assertEquals("artifact", event.name);
        assertEquals("call_1", event.callId);
        var data = JsonUtil.OBJECT_MAPPER.readTree(event.data);
        assertEquals(".core-ai/media/images/image-x.png", data.get("path").asText());
        assertEquals("image", data.get("kind").asText());
        assertEquals("generate_image", data.get("tool").asText());
        assertEquals(4, data.get("size").asInt());
    }

    @Test
    void writesVideoUnderVideosWithVideoTool() throws Exception {
        var sink = new LocalArtifactSink(workspace, context(true, true));

        sink.save("video-abc.mp4", "video/mp4", new byte[]{9});

        assertTrue(Files.exists(workspace.resolve(".core-ai/media/videos/video-abc.mp4")));
        var data = JsonUtil.OBJECT_MAPPER.readTree(((CustomEvent) dispatched.getFirst()).data);
        assertEquals("video", data.get("kind").asText());
        assertEquals("get_video_status", data.get("tool").asText());
        assertEquals(".core-ai/media/videos/video-abc.mp4", data.get("path").asText());
    }

    @Test
    void infersToolImagePrefix() throws Exception {
        var sink = new LocalArtifactSink(workspace, context(true, true));

        sink.save("tool-image-y.png", "image/png", new byte[]{1});

        var data = JsonUtil.OBJECT_MAPPER.readTree(((CustomEvent) dispatched.getFirst()).data);
        assertEquals("tool-image", data.get("tool").asText());
    }

    @Test
    void writesWithoutEmitter() throws Exception {
        var sink = new LocalArtifactSink(workspace, context(true, false));

        sink.save("image-y.png", "image/png", new byte[]{1});

        assertTrue(Files.exists(workspace.resolve(".core-ai/media/images/image-y.png")));
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void skipsEventOutsideTurn() throws Exception {
        var sink = new LocalArtifactSink(workspace, context(false, true));

        sink.save("image-z.png", "image/png", new byte[]{1});

        assertTrue(Files.exists(workspace.resolve(".core-ai/media/images/image-z.png")));
        assertTrue(dispatched.isEmpty());
    }
}
