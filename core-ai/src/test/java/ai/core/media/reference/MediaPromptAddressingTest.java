package ai.core.media.reference;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class MediaPromptAddressingTest {
    private static Map<String, String> tokens(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (var i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    @Test
    void rewritesDeclaredNamesToPositionalTokens() {
        var rewritten = MediaPromptAddressing.rewrite(
                "参考 @char_lin 的角色，放进 @scene_cafe 的场景",
                tokens("char_lin", "@Image1", "scene_cafe", "@Image2"), Set.of());

        assertEquals("参考 @Image1 的角色，放进 @Image2 的场景", rewritten.prompt());
        assertTrue(rewritten.unmentionedNames().isEmpty());
    }

    @Test
    void neverMangesTextThatMerelyContainsAnAtSign() {
        var rewritten = MediaPromptAddressing.rewrite(
                "email lin@char_lin.com and the handle @char_linked stay as they are, but @char_lin does not",
                tokens("char_lin", "<Picture 1>"), Set.of());

        assertEquals("email lin@char_lin.com and the handle @char_linked stay as they are, but <Picture 1> does not",
                rewritten.prompt());
    }

    @Test
    void longerNameWinsOverItsOwnPrefix() {
        var rewritten = MediaPromptAddressing.rewrite("@char and @char_lin",
                tokens("char", "@Image1", "char_lin", "@Image2"), Set.of());

        assertEquals("@Image1 and @Image2", rewritten.prompt());
    }

    @Test
    void removesMentionsOfTrimmedReferences() {
        // a trimmed reference renumbers everything after it; leaving its mention in place would address
        // an asset that is no longer in the array
        var rewritten = MediaPromptAddressing.rewrite("keep @a, drop @b",
                tokens("a", "@Image1"), Set.of("b"));

        assertEquals("keep @Image1, drop ", rewritten.prompt());
    }

    @Test
    void reportsDeclaredNamesThePromptNeverMentions() {
        var rewritten = MediaPromptAddressing.rewrite("only @a is described",
                tokens("a", "@Image1", "b", "@Image2"), Set.of());

        assertEquals(List.of("b"), rewritten.unmentionedNames());
    }

    @Test
    void rejectsNamesThatCannotBeAddressed() {
        assertTrue(MediaPromptAddressing.isValidName("char_lin"));
        assertTrue(MediaPromptAddressing.isValidName("shot-1"));
        assertFalse(MediaPromptAddressing.isValidName("char lin"));
        assertFalse(MediaPromptAddressing.isValidName(""));
        assertFalse(MediaPromptAddressing.isValidName(null));
    }
}
