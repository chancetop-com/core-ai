package ai.core.server.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxAttachmentPathTest {
    @Test
    void createsTmpTargetForSafeFileName() {
        assertEquals("/tmp/社媒数据.xlsx", SandboxAttachmentPath.targetPath("社媒数据.xlsx"));
    }

    @Test
    void rejectsPathTraversalAndSeparators() {
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath("../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath("nested/file.txt"));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath("nested\\file.txt"));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(".."));
    }

    @Test
    void rejectsBlankAndNulNames() {
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath(" "));
        assertThrows(IllegalArgumentException.class, () -> SandboxAttachmentPath.targetPath("bad\0name"));
    }
}
