package ai.core.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayHeaderCodecTest {
    @Test
    void keepsAsciiValuesAsIs() {
        assertEquals("menu-agent", GatewayHeaderCodec.encode("menu-agent"));
        assertEquals("menu-agent", GatewayHeaderCodec.decode("menu-agent"));
        assertEquals("conversation-1", GatewayHeaderCodec.encode("conversation-1"));
    }

    @Test
    void encodesNonAsciiValuesAsRfc2047EncodedWord() {
        var name = "Docs 构建修复助手";
        var expected = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "?=";

        var encoded = GatewayHeaderCodec.encode(name);

        assertEquals(expected, encoded);
        assertEquals(name, GatewayHeaderCodec.decode(encoded));
    }

    @Test
    void decodeKeepsMalformedEncodedWordsAsPlain() {
        assertEquals("=?UTF-8?B?not-valid-base64!?=", GatewayHeaderCodec.decode("=?UTF-8?B?not-valid-base64!?="));
        assertEquals("=?UTF-8?B??=", GatewayHeaderCodec.decode("=?UTF-8?B??="));
    }
}
