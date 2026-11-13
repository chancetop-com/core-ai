package ai.core.server.share;

import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.SessionShare;
import ai.core.server.domain.SessionShareFile;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SessionShareProjectionTest {
    private final SessionShareProjection projection = new SessionShareProjection();
    private final SessionShareFileService files = mock(SessionShareFileService.class);
    private final SessionShare share = new SessionShare();

    @BeforeEach
    void setUp() {
        projection.fileService = files;
        share.token = "a".repeat(43);
        share.traceIds = List.of("trace-1");
        when(files.rewrite(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void messageOmitsSandboxConnectionAndInteractiveCardButPreservesDebugPayload() {
        var message = new ChatMessage();
        message.role = "agent";
        message.seq = 2L;
        message.traceId = "trace-1";
        message.content = "result";
        message.sandbox = new ChatMessage.SandboxRecord();
        message.sandbox.hostname = "private.internal";
        var event = new ChatMessage.EventRecord();
        event.name = "debug";
        event.data = "{\"api_key\":\"secret-value\",\"error\":\"failed\"}";
        event.card = "{\"action\":\"approve\"}";
        message.events = List.of(event);
        var tool = new ChatMessage.ToolCallRecord();
        tool.arguments = "{\"headers\":{\"Authorization\":\"Bearer token-value\"},\"query\":\"where\"}";
        tool.result = "{\"error\":\"Image not found\"}";
        message.tools = List.of(tool);

        var result = projection.message(message, share);

        assertNull(result.sandbox);
        assertNull(result.events.getFirst().card);
        assertTrue(result.events.getFirst().data.contains("failed"));
        assertFalse(result.events.getFirst().data.contains("secret-value"));
        assertTrue(result.tools.getFirst().arguments.contains("where"));
        assertFalse(result.tools.getFirst().arguments.contains("token-value"));
        assertEquals("trace-1", result.traceId);
        assertEquals(2L, result.seq);
    }

    @Test
    void traceRedactsAttributeKeysAndNestedPayloadsWithoutDisclosingAccount() {
        var trace = new Trace();
        trace.traceId = "trace-1";
        trace.userId = "private-owner";
        trace.agentId = "private-agent";
        trace.input = "{\"system\":\"Read this remembered fact\",\"headers\":{\"cookie\":\"session-secret\"}}";
        trace.metadata = Map.of("Authorization", "secret-header", "task", "debug image");

        var result = projection.trace(trace, share);

        assertNull(result.account);
        assertNull(result.userId);
        assertNull(result.agentId);
        assertTrue(result.input.contains("Read this remembered fact"));
        assertFalse(result.input.contains("session-secret"));
        assertEquals("[redacted]", result.metadata.get("Authorization"));
        assertEquals("debug image", result.metadata.get("task"));
    }

    @Test
    void spanAppliesTheSameRedactionToRawFields() {
        var span = new Span();
        span.traceId = "trace-1";
        span.spanId = "span-1";
        span.userId = "owner-private";
        span.output = "{\"access_token\":\"secret-token\",\"answer\":42}";
        span.attributes = Map.of("api_key", "secret-key");

        var view = projection.span(span, share);

        assertFalse(view.output.contains("secret-token"));
        assertTrue(view.output.contains("42"));
        assertEquals("[redacted]", view.attributes.get("api_key"));
        assertNull(view.userId);
    }

    @Test
    void mapsOnlyManifestFilesToRevocablePaths() {
        var file = new SessionShareFile();
        file.id = "manifest-1";
        file.fileId = "private-file-id";
        file.blobName = "private/blob";
        file.fileName = "screenshot.png";

        var artifact = projection.artifacts(List.of(file), share).getFirst();

        assertEquals("manifest-1", artifact.fileId);
        assertEquals("/api/public/session-shares/" + share.token + "/files/manifest-1/content", artifact.downloadUrl);
        assertNull(projection.artifacts(List.of(file), null).getFirst().downloadUrl);
    }

    @Test
    void omitsTraceLinksNotInManifestAndOversizedPublicPayloads() {
        var message = new ChatMessage();
        message.traceId = "not-shared";
        message.content = "x".repeat(2_000_001);

        var result = projection.message(message, share);

        assertNull(result.traceId);
        assertTrue(result.content.startsWith("[Payload omitted:"));
    }

    @Test
    void rewritesFileLinksBeforeRedactingCredentials() {
        when(files.rewrite("private file link", share)).thenReturn("shared file link");

        assertEquals("shared file link", projection.text("private file link", share));
    }
}
