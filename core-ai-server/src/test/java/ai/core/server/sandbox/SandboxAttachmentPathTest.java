package ai.core.server.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxAttachmentPathTest {
    @Test
    void createsTmpTargetForSafeFileName() {
        assertEquals("/tmp/attachments/社媒数据.xlsx",
                SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "session-1", "社媒数据.xlsx"));
    }

    @Test
    void createsSessionDirectoryInsideTheDurableWorkspace() {
        assertEquals("/workspace/attachments/session-1/photo.jpg",
                SandboxAttachmentPath.targetPath(SandboxAttachmentPath.WORKSPACE_ROOT, "session-1", "photo.jpg"));
        // the scratch fallback keeps the legacy flat layout
        assertEquals("/tmp/attachments/photo.jpg",
                SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "session-1", "photo.jpg"));
    }

    @Test
    void picksTheWorkspaceUnlessTheProviderMountsItReadOnly() {
        assertEquals(SandboxAttachmentPath.WORKSPACE_ROOT, SandboxAttachmentPath.root(true));
        assertEquals(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, SandboxAttachmentPath.root(false));
    }

    @Test
    void acceptsRecordedTargetsFromEitherRootAndTheSessionLayout() {
        assertTrue(SandboxAttachmentPath.isSafeTarget("photo.jpg", "/workspace/attachments/photo.jpg"));
        assertTrue(SandboxAttachmentPath.isSafeTarget("photo.jpg", "/workspace/attachments/session-1/photo.jpg"));
        assertTrue(SandboxAttachmentPath.isSafeTarget("photo.jpg", "/tmp/attachments/photo.jpg"));
        // attachments staged before the dedicated directories existed
        assertTrue(SandboxAttachmentPath.isSafeTarget("metrics.xlsx", "/tmp/metrics.xlsx"));
        assertFalse(SandboxAttachmentPath.isSafeTarget("photo.jpg", "/etc/photo.jpg"));
        assertFalse(SandboxAttachmentPath.isSafeTarget("../photo.jpg", "/workspace/attachments/../photo.jpg"));
        assertFalse(SandboxAttachmentPath.isSafeTarget("photo.jpg", "/workspace/attachments/a/b/photo.jpg"));
        assertFalse(SandboxAttachmentPath.isSafeTarget("photo.jpg", "/workspace/attachments/session-1/other.jpg"));
    }

    @Test
    void rejectsPathTraversalAndSeparators() {
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "session-1", "../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "session-1", "nested/file.txt"));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "session-1", "nested\\file.txt"));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "session-1", ".."));
    }

    @Test
    void aHostileSessionIdFallsBackToTheFlatRoot() {
        assertEquals("/workspace/attachments/photo.jpg",
                SandboxAttachmentPath.targetPath(SandboxAttachmentPath.WORKSPACE_ROOT, "../session", "photo.jpg"));
    }

    @Test
    void rejectsBlankAndNulNames() {
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "session-1", " "));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "session-1", "bad\0name"));
    }
}
