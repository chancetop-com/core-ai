package ai.core.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class TokenizerTest {
    // text containing a special token literal, jtokkit rejects those in the non-ordinary encode/countTokens
    private static final String SPECIAL_TOKEN_TEXT = "data = enc.encode(text, allowed_special={\"<|endoftext|>\"})";

    @Test
    void tokenCountWithSpecialTokenLiteral() {
        assertTrue(Tokenizer.tokenCount(SPECIAL_TOKEN_TEXT) > 0);
    }

    @Test
    void encodeWithSpecialTokenLiteral() {
        assertFalse(Tokenizer.encode(SPECIAL_TOKEN_TEXT).isEmpty());
    }

    @Test
    void truncateWithSpecialTokenLiteral() {
        assertFalse(Tokenizer.truncate(SPECIAL_TOKEN_TEXT, 5).isEmpty());
    }

    @Test
    void tokenCountWithNullOrEmpty() {
        assertEquals(0, Tokenizer.tokenCount(null));
        assertEquals(0, Tokenizer.tokenCount(""));
    }

    @Test
    void encodeDecodeRoundTrip() {
        assertEquals(SPECIAL_TOKEN_TEXT, Tokenizer.decode(Tokenizer.encode(SPECIAL_TOKEN_TEXT)));
    }
}
