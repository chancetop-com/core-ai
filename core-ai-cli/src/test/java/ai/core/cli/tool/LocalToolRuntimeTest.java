package ai.core.cli.tool;

import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.hub.HubCredentialResolver;
import ai.core.llm.domain.Usage;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LocalToolRuntimeTest {
    private static final AuthConfig AUTH = new AuthConfig("http://127.0.0.1:9", "key", "u1", null, null, null, true, false);
    private static final HubCredentialResolver.HubCredentials CREDENTIALS =
            new HubCredentialResolver.HubCredentials("http://127.0.0.1:9", "key", false);

    private static LocalToolRuntime runtime() {
        var props = new Properties();
        props.setProperty("media.api.base", "http://127.0.0.1:9/v1");
        props.setProperty("media.api.key", "key");
        props.setProperty("media.image.model", "test-image");
        props.setProperty("media.video.model", "test-video");
        props.setProperty("media.caption.model", "test-vision");
        props.setProperty("media.summarize_pdf.model", "test-pdf");
        props.setProperty("litellm.api.base", "http://127.0.0.1:9/api/cli/v1");
        props.setProperty("litellm.api.key", "key");
        props.setProperty("llm.model", "test-chat");
        props.setProperty("llm.model.multimodal", "test-vision");
        return LocalToolRuntime.load(Path.of("build/tmp/local-tool-runtime"), CREDENTIALS, AUTH,
                new PropertiesFileSource(props));
    }

    @Test
    void exposesTheMediaAndMultimodalTools() {
        var runtime = runtime();
        var names = runtime.tools().stream().map(LocalToolRuntime.LocalBuiltinTool::name).toList();
        assertEquals(java.util.List.of("generate_image", "generate_video", "get_video_status",
                "caption_image", "summarize_pdf", "decide", "understand_video"), names);
        assertTrue(runtime.tool("generate_image").orElseThrow().description().contains("Default image model for this session: test-image"));
        assertTrue(runtime.tool("generate_video").orElseThrow().description().contains("Default video model for this session: test-video"));
        assertTrue(runtime.tool("understand_video").orElseThrow().description().contains("local video file path"));
        assertEquals(720, runtime.tool("generate_image").orElseThrow().timeoutSeconds());
        assertEquals(120, runtime.tool("generate_video").orElseThrow().timeoutSeconds());
    }

    @Test
    void buildsAContextWithTheLocalProvidersAndModels() {
        var runtime = runtime();
        var context = runtime.context("s1");
        assertEquals("s1", context.getSessionId());
        assertEquals("u1", context.getUserId());
        assertNotNull(context.getLlmProvider());
        assertNotNull(context.getImageMediaProvider());
        assertNotNull(context.getVideoMediaProvider());
        assertNotNull(context.getDecisionProvider());
        assertEquals("test-chat", context.getModel());
        assertEquals("test-vision", context.getMultiModalModel());
        assertEquals("test-image", context.getCustomVariables().get("media.image.model"));
        assertEquals("test-video", context.getCustomVariables().get("media.video.model"));
        assertEquals("test-vision", context.getCustomVariables().get("media.caption.model"));
        assertEquals("test-pdf", context.getCustomVariables().get("media.summarize_pdf.model"));
        assertNotNull(context.getCustomVariables().get("workspace"));
    }

    @Test
    void validatesRequiredArgumentsBeforeCallingTheTool() {
        var runtime = runtime();
        var builtin = runtime.tool("generate_image").orElseThrow();
        var result = runtime.execute(builtin, "{}", 30, runtime.context(""));
        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("missing required parameters: prompt"));
    }

    @Test
    void anEmptyConfigExposesNoTools() {
        var runtime = LocalToolRuntime.load(Path.of("build/tmp/local-tool-runtime"), null, null,
                new PropertiesFileSource(new Properties()));
        assertTrue(runtime.tools().isEmpty());
        assertNull(runtime.tool("generate_image").orElse(null));
        assertFalse(runtime.hasVideo());
    }

    @Test
    void remembersThePendingVideoTaskAcrossProcesses() {
        var workspace = Path.of("build/tmp/local-tool-runtime-video");
        var store = new LocalVideoTaskStore(workspace);
        store.clear("v1");
        assertNull(store.pending());
        store.pending("v1");
        assertEquals("v1", store.pending());
        store.clear("v2");
        assertEquals("v1", store.pending());
        store.clear("v1");
        assertNull(store.pending());
    }

    @Test
    void mapsEveryToolResultOntoTheHubResponseShape() {
        var completed = ToolCommandBase.response(ToolCallResult.completed("ok"), "c1", 5L);
        assertEquals("completed", completed.status);
        assertEquals(Boolean.TRUE, completed.success);
        assertEquals("ok", completed.text);

        var pending = ToolCommandBase.response(ToolCallResult.pending("t1", "wait"), "c1", 5L);
        assertEquals("pending", pending.status);
        assertEquals("t1", pending.taskId);

        var failed = ToolCommandBase.response(ToolCallResult.failed("bad"), "c1", 5L);
        assertEquals("failed", failed.status);
        assertEquals(Boolean.TRUE, failed.isError);
        assertEquals("bad", failed.errorMessage);

        var usage = ToolCommandBase.response(
                ToolCallResult.completed("ok").withLlmUsage("m", new Usage(3, 4, 7)), "c1", 5L);
        assertNotNull(usage.llmUsage);
        assertEquals("m", usage.llmUsage.model);
        assertEquals(3L, usage.llmUsage.inputTokens);
        assertEquals(4L, usage.llmUsage.outputTokens);
    }
}
