package ai.core.server.domain;

import ai.core.tool.tools.UnderstandVideoTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiFileServiceTest {
    private static final UnderstandVideoTool.AttachmentOwner OWNER = new UnderstandVideoTool.AttachmentOwner("user-1", "session-1");
    private static final String FULL_ID = "video_7bb80432-cf17-4742-933b-5049d55ce644";

    private SessionAttachmentRefRepository attachments;
    private GeminiFileService service;

    @BeforeEach
    void setUp() {
        attachments = mock(SessionAttachmentRefRepository.class);
        service = new GeminiFileService();
        service.attachmentRepository = attachments;
    }

    @Test
    void exactIdWins() {
        var reference = reference(FULL_ID);
        when(attachments.findOwned(FULL_ID, "session-1", "user-1")).thenReturn(reference);

        assertSame(reference, service.resolveReference(OWNER, FULL_ID));
        verify(attachments, never()).findOwnedByPrefix(anyString(), anyString(), anyString());
    }

    @Test
    void abbreviatedIdResolvesWhenPrefixIsUnique() {
        var reference = reference(FULL_ID);
        when(attachments.findOwned(any(), any(), any())).thenReturn(null);
        when(attachments.findOwnedByPrefix("video_7bb80432", "session-1", "user-1")).thenReturn(List.of(reference));

        assertSame(reference, service.resolveReference(OWNER, "video_7bb80432"));
    }

    @Test
    void ambiguousPrefixNamesTheCandidates() {
        when(attachments.findOwned(any(), any(), any())).thenReturn(null);
        when(attachments.findOwnedByPrefix(eq("video_7bb80432"), any(), any()))
                .thenReturn(List.of(reference(FULL_ID), reference("video_7bb80432-0000-4742-933b-5049d55ce644")));

        var error = assertThrows(IllegalArgumentException.class, () -> service.resolveReference(OWNER, "video_7bb80432"));

        assertTrue(error.getMessage().contains("ambiguous"), error.getMessage());
        assertTrue(error.getMessage().contains(FULL_ID), error.getMessage());
    }

    @Test
    void missListsTheVideosThisSessionCanWatch() {
        when(attachments.findOwned(any(), any(), any())).thenReturn(null);
        when(attachments.findOwnedByPrefix(any(), any(), any())).thenReturn(List.of());
        when(attachments.findOwnedVideos("session-1", "user-1")).thenReturn(List.of(reference(FULL_ID)));

        var error = assertThrows(IllegalArgumentException.class, () -> service.resolveReference(OWNER, "video_deadbeef"));

        assertTrue(error.getMessage().startsWith("video attachment not found: video_deadbeef"), error.getMessage());
        assertTrue(error.getMessage().contains(FULL_ID), error.getMessage());
    }

    @Test
    void tooShortPrefixIsNotExpanded() {
        when(attachments.findOwned(any(), any(), any())).thenReturn(null);
        when(attachments.findOwnedVideos(any(), any())).thenReturn(List.of());

        var error = assertThrows(IllegalArgumentException.class, () -> service.resolveReference(OWNER, "video_7bb"));

        assertEquals("video attachment not found: video_7bb — pass attachment_reference_id exactly as shown "
                + "(full video_<uuid>, e.g. video_reference_id from drama_list_takes)", error.getMessage());
        verify(attachments, never()).findOwnedByPrefix(anyString(), anyString(), anyString());
    }

    private SessionAttachmentRef reference(String id) {
        var reference = new SessionAttachmentRef();
        reference.id = id;
        reference.sessionId = "session-1";
        reference.userId = "user-1";
        return reference;
    }
}
