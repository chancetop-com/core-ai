package ai.core.server.sandbox;

/** Validates the server-owned landing path for a chat attachment. */
final class SandboxAttachmentPath {
    private static final String TMP_PREFIX = "/tmp/";

    static String targetPath(String fileName) {
        if (fileName == null || fileName.isBlank() || ".".equals(fileName) || "..".equals(fileName)
                || fileName.indexOf('\0') >= 0 || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("invalid sandbox attachment file name");
        }
        return TMP_PREFIX + fileName;
    }

    static boolean isSafeTarget(String fileName, String targetPath) {
        try {
            return targetPath != null && targetPath.equals(targetPath(fileName));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private SandboxAttachmentPath() {
    }
}
