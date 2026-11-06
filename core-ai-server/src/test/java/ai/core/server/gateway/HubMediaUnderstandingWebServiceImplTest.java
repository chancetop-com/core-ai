package ai.core.server.gateway;

import ai.core.api.server.hubmedia.HubMediaUnderstandVideoRequest;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.web.auth.AuthContext;
import ai.core.tool.tools.UnderstandVideoTool;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class HubMediaUnderstandingWebServiceImplTest {
    private static final String USER_ID = "u1";
    private static final String SESSION_ID = "s1";

    private HubMediaUnderstandingWebServiceImpl impl;
    private UnderstandVideoTool.VideoUnderstandingService videoService;
    private SessionAttachmentRefRepository attachmentRepository;
    private ObjectStorageServiceResolver resolver;
    private ObjectStorageService storage;

    @BeforeEach
    void setUp() {
        impl = new HubMediaUnderstandingWebServiceImpl();
        videoService = mock(UnderstandVideoTool.VideoUnderstandingService.class);
        attachmentRepository = mock(SessionAttachmentRefRepository.class);
        resolver = mock(ObjectStorageServiceResolver.class);
        storage = mock(ObjectStorageService.class);

        var webContext = mock(WebContext.class);
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn(USER_ID);
        impl.webContext = webContext;
        impl.videoUnderstandingService = videoService;
        impl.attachmentRepository = attachmentRepository;
        impl.objectStorageResolver = resolver;
    }

    private HubMediaUnderstandVideoRequest request() {
        var request = new HubMediaUnderstandVideoRequest();
        request.sessionId = SESSION_ID;
        request.question = "what happens?";
        return request;
    }

    private UnderstandVideoTool.VideoUnderstandingResult result() {
        return new UnderstandVideoTool.VideoUnderstandingResult("a cat", "gemini-video", "miss", 10, 20, 30);
    }

    @Test
    void understandsAnExistingReferenceAsTheCaller() {
        var request = request();
        request.attachmentReferenceId = "video_1";
        when(videoService.understand(any(), eq("video_1"), any(), eq("what happens?"))).thenReturn(result());

        var response = impl.understandVideo(request);

        assertEquals("a cat", response.answer);
        assertEquals("video_1", response.attachmentReferenceId);
        assertEquals(SESSION_ID, response.sessionId);
        assertEquals(10L, response.promptTokens);
        verify(videoService).understand(eq(new UnderstandVideoTool.AttachmentOwner(USER_ID, SESSION_ID)),
                eq("video_1"), eq(null), eq("what happens?"));
    }

    @Test
    void mintsAReferenceForAnUploadedBlob() {
        var request = request();
        request.container = "static";
        request.blobName = "ai/uploads/8f0c6e7e-5f4a-4a1e-9f4b-0f7f0f7f0f7f.mp4";
        request.fileName = "shot.mp4";
        when(resolver.resolve()).thenReturn(storage);
        when(resolver.multimodalContainer()).thenReturn("static");
        when(storage.headObject("static", request.blobName)).thenReturn(
                new ObjectStorageService.ObjectMetadata(1024L, "etag-1", "video/mp4", "now"));
        when(videoService.understand(any(), any(), any(), any())).thenReturn(result());

        var response = impl.understandVideo(request);

        var captor = ArgumentCaptor.forClass(SessionAttachmentRef.class);
        verify(attachmentRepository).insert(captor.capture());
        var reference = captor.getValue();
        assertNotNull(reference.id);
        assertEquals(SESSION_ID, reference.sessionId);
        assertEquals(USER_ID, reference.userId);
        assertEquals("static", reference.container);
        assertEquals("etag-1", reference.sourceETag);
        assertEquals(1024L, reference.sourceSizeBytes);
        assertEquals("video/mp4", reference.contentType);
        assertEquals(reference.id, response.attachmentReferenceId);
        verify(videoService).understand(eq(new UnderstandVideoTool.AttachmentOwner(USER_ID, SESSION_ID)),
                eq(reference.id), eq(null), eq("what happens?"));
    }

    @Test
    void reusesTheReferenceAlreadyMintedForTheSameBlob() {
        var request = request();
        request.container = "static";
        request.blobName = "ai/uploads/8f0c6e7e-5f4a-4a1e-9f4b-0f7f0f7f0f7f.mp4";
        var existing = new SessionAttachmentRef();
        existing.id = "video_existing";
        when(resolver.multimodalContainer()).thenReturn("static");
        when(attachmentRepository.findBySource(SESSION_ID, USER_ID, "static", request.blobName)).thenReturn(existing);
        when(videoService.understand(any(), any(), any(), any())).thenReturn(result());

        var response = impl.understandVideo(request);

        assertEquals("video_existing", response.attachmentReferenceId);
        verify(attachmentRepository, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void rejectsAnUploadFromAnotherContainer() {
        var request = request();
        request.container = "sandbox";
        request.blobName = "ai/uploads/x.mp4";
        when(resolver.resolve()).thenReturn(storage);
        when(resolver.multimodalContainer()).thenReturn("static");

        assertThrows(BadRequestException.class, () -> impl.understandVideo(request));
    }

    @Test
    void rejectsAnEmptyQuestion() {
        var request = request();
        request.question = " ";
        request.attachmentReferenceId = "video_1";

        assertThrows(BadRequestException.class, () -> impl.understandVideo(request));
    }

    @Test
    void requiresEitherAReferenceOrBlobCoordinates() {
        assertThrows(BadRequestException.class, () -> impl.understandVideo(request()));
    }
}
