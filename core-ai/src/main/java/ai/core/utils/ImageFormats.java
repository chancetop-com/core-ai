package ai.core.utils;

import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Content-based image detection for payloads that are about to be handed to a model as an image.
 * A file name, an extension or a client-declared media type is a claim, not evidence: an error body saved as
 * {@code probe.png} travels through read_file into the chat history, and the model API then rejects the whole
 * request ("You have uploaded an unsupported image"), killing a run far away from the mistake. The first bytes
 * of the payload are checked here instead, so a producer can decline to declare an image and a consumer can
 * decline to send one.
 *
 * @author Stephen
 */
public final class ImageFormats {
    /** formats the model APIs decode; an image in any other format must be converted before it is sent */
    public static final String READABLE_FORMAT_LIST = "png/jpeg/webp/gif";

    private static final Set<String> MODEL_READABLE = Set.of("png", "jpeg", "webp", "gif");
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF_87A_MAGIC = {'G', 'I', 'F', '8', '7', 'a'};
    private static final byte[] GIF_89A_MAGIC = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] WEBP_RIFF_MAGIC = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};
    private static final byte[] BMP_MAGIC = {'B', 'M'};
    private static final byte[] TIFF_LITTLE_ENDIAN_MAGIC = {'I', 'I', 0x2A, 0x00};
    private static final byte[] TIFF_BIG_ENDIAN_MAGIC = {'M', 'M', 0x00, 0x2A};
    /** the longest signature is WEBP's, which needs 12 bytes; 32 base64 characters carry 24 */
    private static final int SIGNATURE_BASE64_CHARS = 32;

    /**
     * @return the image format the bytes really are, or null when they are not an image
     */
    public static String detect(byte[] bytes) {
        if (bytes == null) return null;
        if (startsWith(bytes, PNG_MAGIC)) return "png";
        if (startsWith(bytes, JPEG_MAGIC)) return "jpeg";
        if (startsWith(bytes, GIF_87A_MAGIC) || startsWith(bytes, GIF_89A_MAGIC)) return "gif";
        if (startsWith(bytes, WEBP_RIFF_MAGIC) && matches(bytes, 8, WEBP_MAGIC)) return "webp";
        if (startsWith(bytes, BMP_MAGIC)) return "bmp";
        if (startsWith(bytes, TIFF_LITTLE_ENDIAN_MAGIC) || startsWith(bytes, TIFF_BIG_ENDIAN_MAGIC)) return "tiff";
        return null;
    }

    /**
     * @return the image format the base64 payload really is, or null when it is not an image
     */
    public static String detectBase64(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        var prefix = base64Prefix(base64);
        if (prefix.isEmpty()) return null;
        try {
            return detect(Base64.getDecoder().decode(prefix));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The leading base64 characters of a payload, freed of a data-uri prefix and of the line wrapping some
     * clients apply, truncated to whole 4-character groups so that decoding the prefix cannot fail.
     */
    private static String base64Prefix(String value) {
        var comma = value.indexOf(',');
        var offset = comma > 0 && value.regionMatches(true, 0, "data:", 0, 5) ? comma + 1 : 0;
        var prefix = new StringBuilder(SIGNATURE_BASE64_CHARS);
        for (var i = offset; i < value.length() && prefix.length() < SIGNATURE_BASE64_CHARS; i++) {
            var character = value.charAt(i);
            var asciiLetterOrDigit = character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9';
            if (asciiLetterOrDigit || character == '+' || character == '/' || character == '=') prefix.append(character);
        }
        return prefix.substring(0, prefix.length() - prefix.length() % 4);
    }

    /**
     * @return true when the format is one the model APIs decode; null or an unsupported image format is false
     */
    public static boolean isModelReadable(String format) {
        return format != null && MODEL_READABLE.contains(format.toLowerCase(Locale.ROOT));
    }

    public static String mimeType(String format) {
        return "image/" + format.toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] bytes, byte[] magic) {
        return matches(bytes, 0, magic);
    }

    private static boolean matches(byte[] bytes, int offset, byte[] magic) {
        if (bytes.length - offset < magic.length) return false;
        for (var i = 0; i < magic.length; i++) {
            if (bytes[offset + i] != magic[i]) return false;
        }
        return true;
    }

    private ImageFormats() {
    }
}
