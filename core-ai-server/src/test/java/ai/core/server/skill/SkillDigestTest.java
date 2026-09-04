package ai.core.server.skill;

import ai.core.server.domain.SkillResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Shared digest vectors — {@code ai.core.cli.hub.skill.SkillDigestTest} mirrors these
 * exact cases so the server and CLI can never drift apart silently.
 *
 * @author stephen
 */
class SkillDigestTest {
    @Test
    void plainContentWithoutResources() {
        assertEquals("9c508b4ed0e487a992d7e06fd9d34e2683bce82872cef943d99e0a3433927799",
                SkillDigest.of("hello", null));
    }

    @Test
    void resourceOrderDoesNotMatter() {
        var a = resource("references/a.md", "A");
        var b = resource("references/b.md", "B");
        var forward = SkillDigest.of("main", List.of(a, b));
        var reversed = SkillDigest.of("main", List.of(b, a));
        assertEquals("8637b2d12410da081b50b99edac1274d217353461eeb8b808808e6a00d4c6504", forward);
        assertEquals(forward, reversed, "resources are hashed in path order");
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

    private SkillResource resource(String path, String content) {
        var resource = new SkillResource();
        resource.path = path;
        resource.content = content;
        return resource;
    }
}
