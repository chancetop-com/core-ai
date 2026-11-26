package ai.core.cli.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Turns a response body into text for the JSON callers. This client asks for gzip on every text
 * request, so a compressed body is the normal case; the decision is made on the payload bytes
 * instead of {@code Content-Encoding}, which a gateway in front of the server may drop (then the
 * gzip bytes reach every JSON parser as a leading 0x1F), keep over an already decoded body, or
 * leave in place while adding a second layer.
 *
 * @author stephen
 */
final class ResponseBodyDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseBodyDecoder.class);
    private static final String GZIP = "gzip";
    private static final int MAX_ENCODING_LAYERS = 3;
    private static final int PREVIEW_BYTES = 16;

    static String text(URI uri, byte[] body, String contentEncoding) {
        var encoding = contentEncoding == null ? "" : contentEncoding;
        var decoded = decode(uri, body == null ? new byte[0] : body, encoding);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static byte[] decode(URI uri, byte[] body, String encoding) {
        boolean declaredGzip = encoding.toLowerCase(Locale.ROOT).contains(GZIP);
        var current = body;
        int layers = 0;
        while (layers < MAX_ENCODING_LAYERS && isGzip(current)) {
            current = gunzip(uri, current);
            layers++;
        }
        if (layers == 0 && declaredGzip) {
            LOGGER.warn("response declares gzip but the body is not compressed, uri={}", uri);
        }
        if (layers == 1 && !declaredGzip) {
            LOGGER.warn("response body is gzip-compressed without a gzip Content-Encoding, uri={}", uri);
        }
        if (layers > 1) {
            LOGGER.warn("response body carried {} gzip layers, uri={}, declared={}", layers, uri, declaredGzip);
        }
        if (isGzip(current)) {
            throw new ResponseBodyException("response body carries more than " + MAX_ENCODING_LAYERS + " gzip layers, uri=" + uri);
        }
        assertUtf8(uri, current, encoding);
        return current;
    }

    private static byte[] gunzip(URI uri, byte[] body) {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new ResponseBodyException("response body is truncated or corrupt gzip, uri=" + uri
                    + ", error=" + e.getMessage(), e);
        }
    }

    private static void assertUtf8(URI uri, byte[] body, String encoding) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
        } catch (CharacterCodingException e) {
            throw new ResponseBodyException("response body is not UTF-8 text, uri=" + uri
                    + ", content-encoding=" + (encoding.isBlank() ? "(none)" : encoding)
                    + ", first bytes=" + hexPreview(body), e);
        }
    }

    private static boolean isGzip(byte[] body) {
        return body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B;
    }

    private static String hexPreview(byte[] body) {
        int length = Math.min(body.length, PREVIEW_BYTES);
        if (length == 0) return "(empty)";
        var builder = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) builder.append(' ');
            builder.append(String.format("%02X", body[i]));
        }
        return builder.toString();
    }

    private ResponseBodyDecoder() {
    }
}
