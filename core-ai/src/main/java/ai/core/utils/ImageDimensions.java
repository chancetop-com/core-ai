package ai.core.utils;

import java.util.Base64;

/**
 * Pixel dimensions read from an image payload's header without decoding the pixels — naming a payload's size in
 * a diagnostic must not need the AWT decoders a GraalVM native image cannot link. PNG, JPEG and GIF are parsed;
 * anything unreadable, truncated or of another format is null, never an exception.
 *
 * @author stephen
 */
public final class ImageDimensions {
    /**
     * @param dataUriOrBase64 a {@code data:} URI or a bare base64 payload
     * @return the size the payload declares, or null when it cannot be read as a png/jpeg/gif image
     */
    public static Size ofBase64(String dataUriOrBase64) {
        var bytes = decode(dataUriOrBase64);
        if (bytes == null) return null;
        return of(bytes, ImageFormats.detect(bytes));
    }

    /**
     * @return the size the header declares, or null when it is unreadable or of an unsupported format
     */
    public static Size of(byte[] bytes, String format) {
        if (bytes == null || format == null) return null;
        return switch (format) {
            case "png" -> png(bytes);
            case "jpeg" -> jpeg(bytes);
            case "gif" -> gif(bytes);
            default -> null;
        };
    }

    private static byte[] decode(String value) {
        if (value == null || value.isEmpty()) return null;
        var comma = value.indexOf(',');
        var payload = comma > 0 && value.regionMatches(true, 0, "data:", 0, 5) ? value.substring(comma + 1) : value;
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Size png(byte[] bytes) {
        if (bytes.length < 24) return null;
        return new Size(readIntBigEndian(bytes, 16), readIntBigEndian(bytes, 20));
    }

    private static Size gif(byte[] bytes) {
        if (bytes.length < 10) return null;
        return new Size(readShortLittleEndian(bytes, 6), readShortLittleEndian(bytes, 8));
    }

    private static Size jpeg(byte[] bytes) {
        var index = 2;
        while (index + 3 < bytes.length) {
            if ((bytes[index] & 0xFF) != 0xFF) {
                index++;
                continue;
            }
            var marker = bytes[index + 1] & 0xFF;
            if (marker == 0xFF || marker == 0x00 || marker == 0x01 || marker >= 0xD0 && marker <= 0xD9) {
                index += 2;
                continue;
            }
            if (isStartOfFrame(marker)) {
                if (index + 8 >= bytes.length) return null;
                return new Size(readShortBigEndian(bytes, index + 7), readShortBigEndian(bytes, index + 5));
            }
            // every other segment carries its payload length, walk to the next marker
            var length = readShortBigEndian(bytes, index + 2);
            if (length < 2) return null;
            index += 2 + length;
        }
        return null;
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static int readShortBigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static int readShortLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private ImageDimensions() {
    }

    /** Pixel dimensions of an image payload. */
    public record Size(int width, int height) {
        /** The {@code WxH} form the media tools use for the size parameter. */
        public String describe() {
            return width + "x" + height;
        }
    }
}
