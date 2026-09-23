package ai.core.server.sandbox;

import java.util.List;

/**
 * Validates the server-owned landing path for a chat attachment. Platform-staged files land in a dedicated
 * directory of the session's working area so they never collide with what the agent writes there itself; a
 * sandbox whose workspace is mounted read-only falls back to the same layout under {@code /tmp}.
 *
 * @author stephen
 */
final class SandboxAttachmentPath {
    static final String WORKSPACE_ROOT = "/workspace/attachments";
    static final String ATTACHMENT_TMP_ROOT = "/tmp/attachments";
    /** the "sandbox" category of chat attachments lands directly in the scratch dir, as it always has */
    static final String CHAT_FILE_ROOT = "/tmp";
    private static final List<String> KNOWN_ROOTS = List.of(WORKSPACE_ROOT, ATTACHMENT_TMP_ROOT, CHAT_FILE_ROOT);

    static String root(boolean workspaceWritable) {
        return workspaceWritable ? WORKSPACE_ROOT : ATTACHMENT_TMP_ROOT;
    }

    static String targetPath(String root, String fileName) {
        if (!valid(fileName)) throw new IllegalArgumentException("invalid sandbox attachment file name");
        return root + "/" + fileName;
    }

    static boolean isSafeTarget(String fileName, String targetPath) {
        if (targetPath == null || !valid(fileName)) return false;
        var suffix = "/" + fileName;
        return KNOWN_ROOTS.stream().anyMatch(known -> (known + suffix).equals(targetPath));
    }

    static boolean valid(String fileName) {
        if (fileName == null || fileName.isBlank() || ".".equals(fileName) || "..".equals(fileName)) return false;
        return fileName.indexOf('\0') < 0 && fileName.indexOf('/') < 0 && fileName.indexOf('\\') < 0;
    }

    private SandboxAttachmentPath() {
    }
}
