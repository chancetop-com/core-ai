package ai.core.server.a2a;

import ai.core.api.a2a.Message;
import ai.core.api.a2a.Part;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.sandbox.SandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What an inbound A2A message does with its file parts: a foreign URL is handed to the agent, a file in
 * the platform's storage is staged and hinted, an image reaches the model, and a video is referenced
 * without leaking its URL into the text — the chat message treatment, applied to a hub run.
 *
 * @author stephen
 */
class A2AMessageAttachmentsTest {
    private static final String SESSION = "s-1";
    private static final String USER = "u-1";
    private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private SandboxService sandboxService;
    private ObjectStorageService storage;
    private SessionAttachmentRefRepository repository;
    private A2AMessageAttachments attachments;

    @BeforeEach
    void setUp() {
        sandboxService = mock(SandboxService.class);
        var resolver = mock(ObjectStorageServiceResolver.class);
        storage = mock(ObjectStorageService.class);
        repository = mock(SessionAttachmentRefRepository.class);
        when(resolver.multimodalContainer()).thenReturn("static");
        when(resolver.resolve()).thenReturn(storage);
        attachments = new A2AMessageAttachments();
        attachments.sandboxService = sandboxService;
        attachments.objectStorageResolver = resolver;
        attachments.attachmentRepository = repository;
    }

    @Test
    void noFilePartsLeaveTheMessageUntouched() {
        var delivered = attachments.deliver(SESSION, USER, Message.user("hello"), "hello");

        assertEquals("hello", delivered.text());
        assertNull(delivered.contents());
        verifyNoInteractions(sandboxService, storage, repository);
    }

    @Test
    void aForeignUrlIsHandedToTheAgentAsIs() {
        var delivered = attachments.deliver(SESSION, USER,
                message(Part.file("a.pdf", "application/pdf", "https://host/a.pdf")), "read this");

        assertEquals("read this\n\nhttps://host/a.pdf", delivered.text());
        assertNull(delivered.contents());
        verifyNoInteractions(sandboxService);
    }

    @Test
    void aPlatformFileIsStagedAndHintedWithoutASecondUrl() {
        var blobName = "uploads/notes.docx";
        when(sandboxService.stageAttachments(eq(SESSION), eq(USER), any())).thenReturn(Map.of(blobName, "/tmp/notes.docx"));

        var delivered = attachments.deliver(SESSION, USER,
                message(filePart("notes.docx", DOCX_TYPE, "https://cdn/" + blobName, "sandbox", blobName)), "see notes");

        assertTrue(delivered.text().contains("/tmp/notes.docx"), delivered.text());
        assertFalse(delivered.text().contains("https://cdn/"), delivered.text());
        assertNull(delivered.contents());
    }

    @Test
    void anImageInTheMultimodalContainerReachesTheModel() {
        var blobName = "ai/uploads/pic.png";
        when(storage.downloadObject("static", blobName)).thenReturn(new byte[]{1, 2, 3});

        var delivered = attachments.deliver(SESSION, USER,
                message(filePart("pic.png", "image/png", "https://cdn/" + blobName, "static", blobName)), "what is this");

        assertEquals(1, delivered.contents().size());
        assertTrue(delivered.text().contains("https://cdn/" + blobName), delivered.text());
        verify(storage).downloadObject("static", blobName);
    }

    @Test
    void aVideoIsReferencedWithoutSerializingItsUrlIntoTheText() {
        var blobName = "ai/uploads/clip.mp4";
        when(storage.headObject("static", blobName))
                .thenReturn(new ObjectStorageService.ObjectMetadata(10L, "etag-1", "video/mp4", null));

        var delivered = attachments.deliver(SESSION, USER,
                message(filePart("clip.mp4", "video/mp4", "https://cdn/" + blobName, "static", blobName)), "watch this");

        assertEquals(1, delivered.contents().size());
        assertFalse(delivered.text().contains("https://cdn/"), delivered.text());
        verify(repository).insert(any());
    }

    private Message message(Part... fileParts) {
        var message = new Message();
        message.role = "ROLE_USER";
        var parts = new ArrayList<Part>();
        parts.add(Part.text("placeholder"));
        parts.addAll(List.of(fileParts));
        message.parts = parts;
        return message;
    }

    private Part filePart(String fileName, String contentType, String url, String container, String blobName) {
        var part = Part.file(fileName, contentType, url);
        part.metadata = Map.of("container", container, "blobName", blobName);
        return part;
    }
}
