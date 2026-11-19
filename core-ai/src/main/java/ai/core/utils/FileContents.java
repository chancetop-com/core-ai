package ai.core.utils;

/**
 * Content sniffing shared by the file tools and the app-server send parser, which must never hand
 * binary bytes to a text reader or inline them into a message.
 *
 * @author stephen
 */
public final class FileContents {
    private static final int INSPECTION_BYTES = 4096;

    public static boolean looksLikeText(byte[] bytes) {
        var inspected = Math.min(bytes.length, INSPECTION_BYTES);
        var controlBytes = 0;
        for (var i = 0; i < inspected; i++) {
            var value = bytes[i];
            if (value == '\n' || value == '\r' || value == '\t') continue;
            if (value < 0x20) controlBytes++;
        }
        return inspected == 0 || controlBytes * 10 < inspected;
    }

    private FileContents() {
    }
}
