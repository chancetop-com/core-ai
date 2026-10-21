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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LocalSubmitArtifactsToolTest {

    private static String objectArray(String... paths) {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        var array = node.putArray("artifacts");
        for (var path : paths) {
            array.addObject().put("path", path);
        }
        return node.toString();
    }

    @TempDir
    Path workspace;
    @TempDir
    Path outside;

    private final List<AgentEvent> dispatched = new ArrayList<>();

    private ExecutionContext context() {
        var context = ExecutionContext.builder().sessionId("s-1")
                .customVariable(CustomEventEmitter.CONTEXT_KEY,
                        new SessionCustomEventEmitter("s-1", dispatched::add, () -> true))
                .build();
        context.setCurrentToolCallId("call_9");
        return context;
    }

    private LocalSubmitArtifactsTool tool() {
        return LocalSubmitArtifactsTool.create(workspace);
    }

    @Test
    void registersFileRecordsManifestAndPublishesEvent() throws Exception {
        Files.createDirectories(workspace.resolve("reports"));
        Files.writeString(workspace.resolve("reports/weekly.html"), "<h1>Weekly</h1>");

        var result = tool().execute(objectArray("reports/weekly.html"), context());

        assertFalse(result.isFailed());
        var registered = JsonUtil.OBJECT_MAPPER.readTree(result.getResult()).get("registered");
        assertEquals(1, registered.size());
        assertEquals("reports/weekly.html", registered.get(0).get("path").asText());
        assertEquals("weekly.html", registered.get(0).get("name").asText());

        var manifest = Files.readAllLines(LocalArtifactManifest.file(workspace));
        assertEquals(1, manifest.size());
        assertTrue(manifest.getFirst().contains("\"path\":\"reports/weekly.html\""));
        assertTrue(manifest.getFirst().contains("\"sessionId\":\"s-1\""));

        assertEquals(1, dispatched.size());
        var event = (CustomEvent) dispatched.getFirst();
        assertEquals("artifact", event.name);
        assertEquals("call_9", event.callId);
        var data = JsonUtil.OBJECT_MAPPER.readTree(event.data);
        assertEquals("reports/weekly.html", data.get("path").asText());
        assertEquals("file", data.get("kind").asText());
        assertEquals("weekly.html", data.get("name").asText());
        assertEquals("text/html", data.get("contentType").asText());
        assertEquals("submit_artifacts", data.get("tool").asText());
    }

    @Test
    void rejectsFilesOutsideTheWorkspace() throws Exception {
        Files.writeString(outside.resolve("secret.pdf"), "x");

        var result = tool().execute(objectArray(outside.resolve("secret.pdf").toString()), context());

        assertTrue(result.isFailed());
        var failed = JsonUtil.OBJECT_MAPPER.readTree(result.getResult()).get("failed");
        assertEquals(1, failed.size());
        assertEquals("path must point at a file inside the workspace", failed.get(0).get("error").asText());
        assertFalse(Files.exists(LocalArtifactManifest.file(workspace)));
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void reportsPerItemFailuresWithoutFailingTheCall() throws Exception {
        Files.writeString(workspace.resolve("a.md"), "a");

        var result = tool().execute(objectArray("a.md", "missing.pdf"), context());

        assertFalse(result.isFailed());
        var payload = JsonUtil.OBJECT_MAPPER.readTree(result.getResult());
        assertEquals(1, payload.get("registered").size());
        assertEquals(1, payload.get("failed").size());
        assertEquals("file does not exist", payload.get("failed").get(0).get("error").asText());
    }

    @Test
    void acceptsStringItemsAndNormalizesAbsolutePaths() throws Exception {
        Files.createDirectories(workspace.resolve("out"));
        Files.writeString(workspace.resolve("a.md"), "a");
        Files.writeString(workspace.resolve("out/b.csv"), "b,c");
        var args = JsonUtil.OBJECT_MAPPER.createObjectNode();
        var array = args.putArray("artifacts");
        array.add("a.md");
        array.add(workspace.resolve("out/b.csv").toString());

        var result = tool().execute(args.toString(), context());

        assertFalse(result.isFailed());
        var registered = JsonUtil.OBJECT_MAPPER.readTree(result.getResult()).get("registered");
        assertEquals(2, registered.size());
        assertEquals("a.md", registered.get(0).get("path").asText());
        assertEquals("out/b.csv", registered.get(1).get("path").asText());
        assertEquals(2, Files.readAllLines(LocalArtifactManifest.file(workspace)).size());
    }

    @Test
    void unwrapsDoubleEncodedArtifactsField() throws Exception {
        Files.writeString(workspace.resolve("a.md"), "a");
        var inner = JsonUtil.OBJECT_MAPPER.createArrayNode().addObject().put("path", "a.md");
        var args = JsonUtil.OBJECT_MAPPER.createObjectNode();
        args.put("artifacts", inner.toString());

        var result = tool().execute(args.toString(), context());

        assertFalse(result.isFailed());
        assertEquals(1, JsonUtil.OBJECT_MAPPER.readTree(result.getResult()).get("registered").size());
    }

    @Test
    void acceptsASinglePathString() throws Exception {
        Files.writeString(workspace.resolve("a.md"), "a");
        var args = JsonUtil.OBJECT_MAPPER.createObjectNode();
        args.put("artifacts", "a.md");

        var result = tool().execute(args.toString(), context());

        assertFalse(result.isFailed());
        assertEquals(1, JsonUtil.OBJECT_MAPPER.readTree(result.getResult()).get("registered").size());
    }

    @Test
    void rejectsEmptyArtifacts() {
        var result = tool().execute("{\"artifacts\":[]}", context());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("artifacts is required"));
    }
}
