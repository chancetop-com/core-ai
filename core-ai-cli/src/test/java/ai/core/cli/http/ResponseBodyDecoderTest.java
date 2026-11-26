package ai.core.cli.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ResponseBodyDecoderTest {
    private static final URI ENDPOINT = URI.create("http://localhost:18080/api/mcp-hub/servers");
    private static final String JSON = "{\"servers\":[{\"name\":\"stub\"}]}";

    private static String decode(byte[] body, String encoding) {
        return ResponseBodyDecoder.text(ENDPOINT, body, encoding);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] body) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(body);
        }
        return out.toByteArray();
    }

    @Test
    void returnsPlainBodyUnchanged() {
        assertEquals(JSON, decode(bytes(JSON), ""));
    }

    @Test
    void decodesDeclaredGzip() throws IOException {
        assertEquals(JSON, decode(gzip(bytes(JSON)), "gzip"));
    }

    // the reported failure: the client asks for gzip, a gateway answers gzip but drops Content-Encoding,
    // and the raw body reached Jackson as a leading 0x1F ("Illegal character ((CTRL-CHAR, code 31))")
    @Test
    void decodesGzipWithoutContentEncoding() throws IOException {
        assertEquals(JSON, decode(gzip(bytes(JSON)), ""));
    }

    @Test
    void decodesDoubleGzip() throws IOException {
        assertEquals(JSON, decode(gzip(gzip(bytes(JSON))), "gzip"));
    }

    // a gateway that decoded the body but kept the header used to fail with "Not in GZIP format"
    @Test
    void acceptsPlainBodyWithStaleGzipHeader() {
        assertEquals(JSON, decode(bytes(JSON), "gzip"));
    }

    @Test
    void decodesGzipDeclaredWithOtherTokens() throws IOException {
        assertEquals(JSON, decode(gzip(bytes(JSON)), "identity, gzip"));
    }

    @Test
    void rejectsMoreThanThreeGzipLayers() throws IOException {
        var body = gzip(gzip(gzip(gzip(bytes(JSON)))));
        var error = assertThrows(ResponseBodyException.class, () -> decode(body, "gzip"));
        assertTrue(error.getMessage().contains("gzip layers"), error.getMessage());
    }

    @Test
    void rejectsBinaryBodyWithReadableError() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        var error = assertThrows(ResponseBodyException.class, () -> decode(png, ""));
        assertTrue(error.getMessage().contains("not UTF-8 text"), error.getMessage());
        assertTrue(error.getMessage().contains("89 50 4E 47"), error.getMessage());
        assertTrue(error.getMessage().contains(ENDPOINT.toString()), error.getMessage());
    }

    @Test
    void rejectsTruncatedGzipWithReadableError() throws IOException {
        var complete = gzip(bytes(JSON));
        var truncated = new byte[10];
        System.arraycopy(complete, 0, truncated, 0, truncated.length);
        var error = assertThrows(ResponseBodyException.class, () -> decode(truncated, "gzip"));
        assertTrue(error.getMessage().contains("gzip"), error.getMessage());
    }

    // the decoder resolves transport encoding only; an invalid payload must stay invalid so the
    // JSON boundary reports it instead of a lenient parser silently accepting it
    @Test
    void keepsRawControlCharacterInPayload() {
        var payload = "{\"a\":1," + (char) 0x1F + "\"b\":2}";
        assertEquals(payload, decode(bytes(payload), ""));
    }

    @Test
    void keepsEscapedControlCharacterInJsonString() {
        var payload = "{\"a\":\"x\\u001fy\"}";
        assertEquals(payload, decode(bytes(payload), ""));
    }
}
