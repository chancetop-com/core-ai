package ai.core.server.web.sse;

import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author stephen
 */
class CliProxyChannelListenerTest {
    @Test
    void decodeBodyReturnsPlainPayloadUnchanged() {
        byte[] raw = "{\"model\":\"deepseek-flash\"}".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(raw, CliProxyChannelListener.decodeBody(raw, null));
        assertArrayEquals(raw, CliProxyChannelListener.decodeBody(raw, "identity"));
    }

    @Test
    void decodeBodyDecompressesGzipPayload() throws IOException {
        String payload = "{\"model\":\"deepseek-flash\",\"messages\":[\"" + "hello ".repeat(500) + "\"]}";
        byte[] raw = payload.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }

        byte[] decoded = CliProxyChannelListener.decodeBody(out.toByteArray(), "gzip");

        assertEquals(payload, new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    void decodeBodyRejectsCorruptGzipPayload() {
        byte[] invalid = "not-gzip".getBytes(StandardCharsets.UTF_8);

        assertThrows(BadRequestException.class, () -> CliProxyChannelListener.decodeBody(invalid, "gzip"));
    }
}
