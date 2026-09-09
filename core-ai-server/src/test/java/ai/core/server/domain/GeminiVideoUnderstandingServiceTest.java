package ai.core.server.domain;

import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.tool.tools.UnderstandVideoTool;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiVideoUnderstandingServiceTest {
    private static final UnderstandVideoTool.AttachmentOwner OWNER = new UnderstandVideoTool.AttachmentOwner("user-1", "session-1");

    @Test
    void recordedAnswerIsServedWithoutCallingTheModel() {
        var routing = mock(GatewayRoutingEngine.class);
        var config = new GatewayModelConfig();
        config.id = "gemini-video";
        config.providerId = "provider-1";
        config.supportsVideo = true;
        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";
        provider.type = "gemini";
        when(routing.modelConfig("gemini-video")).thenReturn(config);
        when(routing.provider("provider-1")).thenReturn(provider);
        var files = mock(GeminiFileService.class);
        var reference = new SessionAttachmentRef();
        reference.id = "video_1";
        reference.fileId = "file-1";
        when(files.resolveReference(OWNER, "video_1")).thenReturn(reference);
        var memory = mock(VideoUnderstandingMemory.class);
        when(memory.recall(reference, "gemini-video", "one action?"))
                .thenReturn(Optional.of(new VideoUnderstandingMemory.Recalled("yes, one action", "gemini-video")));
        var service = new GeminiVideoUnderstandingService();
        service.routingEngine = routing;
        service.fileService = files;
        service.memory(memory);

        var result = service.understand(OWNER, "video_1", "gemini-video", "one action?");

        assertEquals("yes, one action", result.answer());
        assertEquals("gemini-video", result.model());
        assertEquals("memory", result.fileCache());
        assertEquals(0, result.totalTokens());
        verify(routing, never()).route(any(), any());
        verify(files, never()).ensureActive(any(), any(SessionAttachmentRef.class), any(), any(), any());
        verify(memory, never()).record(any(), any(), any(), any());
    }
}
