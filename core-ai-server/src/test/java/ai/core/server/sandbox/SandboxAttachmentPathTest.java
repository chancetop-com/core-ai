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
                SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "社媒数据.xlsx"));
    }

    @Test
    void createsWorkspaceTargetForSafeFileName() {
        assertEquals("/workspace/attachments/photo.jpg",
                SandboxAttachmentPath.targetPath(SandboxAttachmentPath.WORKSPACE_ROOT, "photo.jpg"));
    }

    @Test
    void picksTheWorkspaceUnlessTheProviderMountsItReadOnly() {
        assertEquals(SandboxAttachmentPath.WORKSPACE_ROOT, SandboxAttachmentPath.root(true));
        assertEquals(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, SandboxAttachmentPath.root(false));
    }

    @Test
    void acceptsRecordedTargetsFromEitherRoot() {
        assertTrue(SandboxAttachmentPath.isSafeTarget("photo.jpg", "/workspace/attachments/photo.jpg"));
        assertTrue(SandboxAttachmentPath.isSafeTarget("photo.jpg", "/tmp/attachments/photo.jpg"));
        // attachments staged before the dedicated directories existed
        assertTrue(SandboxAttachmentPath.isSafeTarget("metrics.xlsx", "/tmp/metrics.xlsx"));
        assertFalse(SandboxAttachmentPath.isSafeTarget("photo.jpg", "/etc/photo.jpg"));
        assertFalse(SandboxAttachmentPath.isSafeTarget("../photo.jpg", "/workspace/attachments/../photo.jpg"));
    }

    @Test
    void rejectsPathTraversalAndSeparators() {
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "nested/file.txt"));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "nested\\file.txt"));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, ".."));
    }

    @Test
    void rejectsBlankAndNulNames() {
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, " "));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(SandboxAttachmentPath.ATTACHMENT_TMP_ROOT, "bad\0name"));
    }
}
