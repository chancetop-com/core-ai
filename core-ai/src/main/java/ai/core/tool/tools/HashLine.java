package ai.core.tool.tools;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Hashline utilities — content-addressable line addressing.
 *
 * Each line is identified by a ref "LINENUM#HASH" (e.g. "5#VK").
 * Hash is xxHash32 of the normalized line content, encoded with a
 * 16-char nibble alphabet. Lines with no alphanumeric chars use the
 * line number as seed to reduce collisions across blank/punctuation lines.
 *
 * Output format per line: {@code LINENUM#HASH:content}
 *
 * @author lim chen
 */
public final class HashLine {
    static final String NIBBLE_ALPHABET = "ZPMQVRWSNKTXJBYH";

    private static final String[] DICT = new String[256];
    private static final Pattern RE_SIGNIFICANT = Pattern.compile("[\\p{L}\\p{N}]");
    // Allows optional leading ">+-" and whitespace before the line number
    private static final Pattern REF_PATTERN =
            Pattern.compile("^\\s*[>+\\-]*\\s*(\\d+)\\s*#\\s*([ZPMQVRWSNKTXJBYH]{2})");
    // Hashline prefix on a content line, e.g. "5#VK:" or "  5#VK:"
    private static final Pattern HASHLINE_PREFIX =
            Pattern.compile("^\\s*[>+\\-]*\\s*\\d+#[ZPMQVRWSNKTXJBYH]{2}:");

    static {
        for (int i = 0; i < 256; i++) {
            int h = (i >>> 4) & 0xF;
            int l = i & 0xF;
            DICT[i] = "" + NIBBLE_ALPHABET.charAt(h) + NIBBLE_ALPHABET.charAt(l);
        }
    }

    public static String computeHash(String line, int lineNumber) {
        String normalized = line.replace("\r", "").stripTrailing();
        int seed = RE_SIGNIFICANT.matcher(normalized).find() ? 0 : lineNumber;
        int hash = xxHash32(normalized.getBytes(StandardCharsets.UTF_8), seed);
        return DICT[hash & 0xFF];
    }

    public static String formatLine(int lineNumber, String hash, String content) {
        return lineNumber + "#" + hash + ":" + content;
    }

    public static LineRef parseRef(String ref) {
        var m = REF_PATTERN.matcher(ref);
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "Invalid line reference \"" + ref + "\". Expected format \"LINE#ID\" (e.g. \"5#VK\").");
        }
        int line = Integer.parseInt(m.group(1));
        if (line < 1) throw new IllegalArgumentException("Line number must be >= 1 in: " + ref);
        return new LineRef(line, m.group(2));
    }

    /**
     * Strip hashline prefixes from content lines that LLMs sometimes copy verbatim.
     * Only strips when ALL non-empty lines carry a hashline prefix.
     */
    public static String[] parseContent(Object raw) {
        if (raw == null) return new String[0];

        String[] lines;
        if (raw instanceof java.util.List<?> list) {
            lines = list.stream().map(Object::toString).toArray(String[]::new);
        } else {
            String s = raw.toString();
            if (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
            lines = s.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        }

        // Strip if every non-empty line has a hashline prefix
        long nonEmpty = 0;
        long prefixed = 0;
        for (String line : lines) {
            if (!line.isBlank()) {
                nonEmpty++;
                if (HASHLINE_PREFIX.matcher(line).find()) prefixed++;
            }
        }
        if (nonEmpty > 0 && prefixed == nonEmpty) {
            String[] stripped = new String[lines.length];
            for (int i = 0; i < lines.length; i++) {
                stripped[i] = HASHLINE_PREFIX.matcher(lines[i]).replaceFirst("");
            }
            return stripped;
        }
        return lines;
    }

    // ── xxHash32 (little-endian, spec-compliant) ──────────────────────────────

    private static final int PRIME1 = (int) 2654435761L;
    private static final int PRIME2 = (int) 2246822519L;
    private static final int PRIME3 = (int) 3266489917L;
    private static final int PRIME4 = (int) 668265263L;
    private static final int PRIME5 = (int) 374761393L;

    static int xxHash32(byte[] data, int seed) {
        int len = data.length;
        int offset = 0;
        int h32;

        if (len >= 16) {
            int v1 = seed + PRIME1 + PRIME2;
            int v2 = seed + PRIME2;
            int v3 = seed;
            int v4 = seed - PRIME1;

            do {
                v1 = Integer.rotateLeft(v1 + (leInt(data, offset) * PRIME2), 13) * PRIME1; offset += 4;
                v2 = Integer.rotateLeft(v2 + (leInt(data, offset) * PRIME2), 13) * PRIME1; offset += 4;
                v3 = Integer.rotateLeft(v3 + (leInt(data, offset) * PRIME2), 13) * PRIME1; offset += 4;
                v4 = Integer.rotateLeft(v4 + (leInt(data, offset) * PRIME2), 13) * PRIME1; offset += 4;
            } while (offset <= len - 16);

            h32 = Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7)
                    + Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18);
        } else {
            h32 = seed + PRIME5;
        }

        h32 += len;

        while (offset <= len - 4) {
            h32 += leInt(data, offset) * PRIME3;
            h32 = Integer.rotateLeft(h32, 17) * PRIME4;
            offset += 4;
        }
        while (offset < len) {
            h32 += (data[offset] & 0xFF) * PRIME5;
            h32 = Integer.rotateLeft(h32, 11) * PRIME1;
            offset++;
        }

        h32 ^= h32 >>> 15;
        h32 *= PRIME2;
        h32 ^= h32 >>> 13;
        h32 *= PRIME3;
        h32 ^= h32 >>> 16;
        return h32;
    }

    private static int leInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    public record LineRef(int lineNumber, String hash) {}

    private HashLine() {}
}
