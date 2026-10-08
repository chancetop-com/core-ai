package ai.core.cli.hub.skill;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Shared digest vectors — {@code ai.core.server.skill.SkillDigestTest} mirrors these exact
 * cases so the server and CLI can never drift apart silently. The expected values were produced
 * by the v1 text algorithm: hashing raw bytes must not move them for UTF-8 content.
 *
 * @author stephen
 */
class SkillDigestTest {
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void plainContentWithoutResources() {
        assertEquals("9c508b4ed0e487a992d7e06fd9d34e2683bce82872cef943d99e0a3433927799",
                SkillDigest.of("hello", null));
        assertEquals("9c508b4ed0e487a992d7e06fd9d34e2683bce82872cef943d99e0a3433927799",
                SkillDigest.of("hello", Map.of()));
    }

    @Test
    void resourceOrderDoesNotMatter() {
        var forward = SkillDigest.of("main", Map.of("references/a.md", utf8("A"), "references/b.md", utf8("B")));
        var reversed = SkillDigest.of("main", Map.of("references/b.md", utf8("B"), "references/a.md", utf8("A")));
        assertEquals("8637b2d12410da081b50b99edac1274d217353461eeb8b808808e6a00d4c6504", forward);
        assertEquals(forward, reversed, "resources are hashed in path order");
    }

    @Test
    void pathOrderIsCaseInsensitive() {
        var digest = SkillDigest.of("main", Map.of(
                "References/B.md", utf8("B"),
                "references/a.md", utf8("A")));
        assertEquals("7e2f8742fd08d885fdf89c4ff4c794d260435efe804d8f0f363989af2774836d", digest);
    }

    @Test
    void lineEndingsArePreservedNotNormalized() {
        var crlf = SkillDigest.of("line1\r\nline2", null);
        var lf = SkillDigest.of("line1\nline2", null);
        assertEquals("886cec347b6d0aa7a74ba7ab20ba9588b1c1af0d7c2a9d8ea5d7611d84988396", crlf);
        assertEquals("1089867e4bc3b4c7b9ea9eb88549ca67b66f0686bc6cb6e1eb9e9ba45a2b16ad", lf);
        assertNotEquals(crlf, lf, "digest must not normalize CRLF to LF");
    }

    @Test
    void nonAsciiContent() {
        assertEquals("293c318e24768439a1dc0742bbd93edea2199a52c6ed327f871107b61743294a",
                SkillDigest.of("你好世界", null));
    }

    @Test
    void binaryResourceBytesAreHashedVerbatim() {
        assertEquals("f43af0f5af31d34026daeebcf848183aee1ec5069e6a7546b6606363f8ee6c65",
                SkillDigest.of("main", Map.of("canvas-fonts/font.ttf", new byte[]{0x00, (byte) 0xFF, 0x10, (byte) 0x80})));
    }

    @Test
    void replacementCharacterBytesAreHashedVerbatim() {
        // U+FFFD is a valid UTF-8 sequence: legacy corrupted resources keep their v1 digest
        assertEquals("3db16fb121d3a4ab5a3a1fa969b46e85ea3009fdd4b544fda58ce273f8ddd3e1",
                SkillDigest.of("main", Map.of("assets/broken.bin", new byte[]{(byte) 0xEF, (byte) 0xBF, (byte) 0xBD})));
    }
}
