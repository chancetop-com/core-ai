package ai.core.server.trace.service;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Moves oversized span/trace bodies out of PostgreSQL: the body is gzipped into the private object
 * store and the column keeps a small reference JSON with a semantic preview. Small bodies stay inline,
 * and every failure falls back to inline so a trace is never dropped because of the object store.
 *
 * @author core-ai
 */
public class TraceBodyStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceBodyStore.class);
    public static final String REF_KEY = "$coreai_blob_ref";
    private static final String REF_SCHEME = "coreai-blob://";
    private static final String BODY_PREFIX = "trace-bodies";
    private static final int MAX_REF_LENGTH = 4096;
    private static final int OUTPUT_PREVIEW_CHARS = 512;
    private static final long MAX_HYDRATE_BYTES = 16L * 1024 * 1024;
    private static final long MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024;
    private static final Pattern UNSAFE_KEY = Pattern.compile("[^A-Za-z0-9._-]");

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] gzip(byte[] raw) {
        try (var buffer = new ByteArrayOutputStream(Math.max(64, raw.length / 8));
             var gzip = new GZIPOutputStream(buffer)) {
            gzip.write(raw);
            gzip.finish();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] gunzip(byte[] compressed) {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             var out = new ByteArrayOutputStream(Math.max(64, compressed.length * 4))) {
            var buffer = new byte[8192];
            long total = 0;
            int read = gzip.read(buffer);
            while (read > 0) {
                total += read;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new IllegalStateException("trace body exceeds " + MAX_DECOMPRESSED_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
                read = gzip.read(buffer);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sanitize(String value) {
        return value == null || value.isBlank() ? "unknown" : UNSAFE_KEY.matcher(value).replaceAll("_");
    }

    /** @return true when the stored value (inline text or body reference) already represents the same body */
    public static boolean sameContent(String existingValue, String newBody) {
        if (existingValue == null || newBody == null) return false;
        if (existingValue.equals(newBody)) return true;
        var node = ref(existingValue);
        if (node == null) return false;
        var expectedSha = node.path("sha256").asText(null);
        if (expectedSha == null || expectedSha.isBlank()) return false;
        var raw = newBody.getBytes(StandardCharsets.UTF_8);
        long expectedBytes = node.path("rawBytes").asLong(-1);
        if (expectedBytes < 0 || raw.length != expectedBytes) return false;
        return expectedSha.equals(sha256(raw));
    }

    private static String referencedKey(JsonNode node) {
        if (node == null) return null;
        var value = node.get(REF_KEY);
        if (value == null || !value.isTextual()) return null;
        var reference = value.asText();
        if (!reference.startsWith(REF_SCHEME)) return null;
        var key = reference.substring(REF_SCHEME.length());
        return key.contains(BODY_PREFIX + "/") ? key : null;
    }

    private static JsonNode ref(String value) {
        if (value == null || value.length() > MAX_REF_LENGTH || !value.startsWith("{") || !value.contains(REF_KEY)) return null;
        try {
            var node = JsonUtil.OBJECT_MAPPER.readTree(value);
            if (node == null || !node.hasNonNull(REF_KEY)) return null;
            return referencedKey(node) == null ? null : node;
        } catch (IOException e) {
            return null;
        }
    }

    @Inject
    ObjectStorageServiceResolver objectStorageResolver;

    private String mode = "false";
    private long inlineMaxBytes = 16L * 1024;
    private String container;
    private String prefix;
    private boolean pgTraceStore;

    /**
     * @param mode          {@code auto} follows object storage availability, {@code true} requires it,
     *                      {@code false} keeps every body inline
     * @param inlineMaxBytes bodies at or below this UTF-8 size stay in PostgreSQL
     */
    public void configure(String mode, long inlineMaxBytes, String container, String prefix, boolean pgTraceStore) {
        this.mode = mode == null || mode.isBlank() ? "auto" : mode;
        if (!pgTraceStore && ("true".equals(this.mode) || "object".equals(this.mode))) {
            throw new IllegalArgumentException("sys.trace.body.offload.enabled requires the pg trace store");
        }
        if (inlineMaxBytes > 0) this.inlineMaxBytes = inlineMaxBytes;
        this.container = container == null || container.isBlank() ? null : container;
        this.prefix = prefix == null || prefix.isBlank() ? null : prefix;
        this.pgTraceStore = pgTraceStore;
    }

    /** @return the original body, or a reference JSON when the body was moved to object storage */
    public String offload(String traceId, String spanId, String field, String body) {
        if (body == null || body.isBlank() || isRef(body)) return body;
        if (body.length() <= inlineMaxBytes / 3) return body;  // UTF-8 is never smaller than the char count
        var raw = body.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= inlineMaxBytes) return body;

        var storage = objectStorage();
        if (storage == null) return body;
        try {
            var compressed = gzip(raw);
            var key = objectKey(traceId, spanId, field);
            storage.uploadObject(container(), key, compressed, "application/json");
            return reference(key, raw.length, compressed.length, sha256(raw), previewOf(field, body));
        } catch (RuntimeException e) {
            // Keeping the body inline costs space but never loses the trace; the next span retries.
            LOGGER.error("failed to offload trace body, keeping it inline, traceId={}, spanId={}, field={}, bytes={}",
                traceId, spanId, field, raw.length, e);
            return body;
        }
    }

    public void offload(Span span) {
        if (span == null) return;
        span.input = offload(span.traceId, span.spanId, "in", span.input);
        span.output = offload(span.traceId, span.spanId, "out", span.output);
    }

    public void offload(Trace trace) {
        if (trace == null) return;
        trace.input = offload(trace.traceId, null, "in", trace.input);
        trace.output = offload(trace.traceId, null, "out", trace.output);
    }

    public boolean isRef(String value) {
        return ref(value) != null;
    }

    /** @return the semantic preview carried by a body reference, or null when the value is not a reference */
    public String preview(String value) {
        var node = ref(value);
        if (node == null) return null;
        var preview = node.get("preview");
        return preview != null && preview.isTextual() ? preview.asText() : null;
    }

    /**
     * @param maxBytes when not strict, a larger body keeps its reference instead of being materialized
     */
    public String hydrate(String value, boolean strict, long maxBytes) {
        var node = ref(value);
        if (node == null) return value;
        long rawBytes = node.path("rawBytes").asLong(0);
        if (rawBytes > maxBytes) {
            if (strict && maxBytes != Long.MAX_VALUE) {
                throw new IllegalStateException("trace body is " + rawBytes + " bytes, above the limit of " + maxBytes);
            }
            if (!strict) return value;
        }

        var storage = objectStorage();
        var key = referencedKey(node);
        if (storage == null) {
            if (strict) throw new IllegalStateException("trace body reference cannot be hydrated: object storage is not configured");
            return value;
        }
        try {
            return new String(gunzip(storage.downloadObject(container(), key)), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            if (strict) throw new IllegalStateException("failed to hydrate trace body: " + key, e);
            LOGGER.warn("failed to hydrate trace body, keeping reference, key={}", key, e);
            return value;
        }
    }

    /** Full body for the detail endpoint, capped to keep one request from exhausting the heap. */
    public String body(String value) {
        return hydrate(value, true, MAX_HYDRATE_BYTES);
    }

    /** Deletes body objects of archived traces; the current month is skipped to avoid racing new writes. */
    public void deleteObjects(List<String> references) {
        if (references == null || references.isEmpty()) return;
        var storage = objectStorage();
        if (storage == null) return;
        var currentMonth = YearMonth.now(ZoneOffset.UTC).toString();
        for (var value : references) {
            var key = referencedKey(ref(value));
            if (key == null) continue;
            var month = monthOf(key);
            if (month != null && month.compareTo(currentMonth) >= 0) continue;
            try {
                storage.deleteObject(container(), key);
            } catch (RuntimeException e) {
                LOGGER.warn("failed to delete trace body object, key={}", key, e);
            }
        }
    }

    private ObjectStorageService objectStorage() {
        if (!pgTraceStore || "false".equals(mode)) return null;
        var service = objectStorageResolver != null ? objectStorageResolver.resolve() : null;
        if (service == null && "true".equals(mode)) {
            throw new IllegalStateException("sys.trace.body.offload.enabled=true but object storage is not configured");
        }
        return service;
    }

    private String container() {
        return container != null ? container : objectStorageResolver.artifactContainer();
    }

    private String objectKey(String traceId, String spanId, String field) {
        var month = YearMonth.now(ZoneOffset.UTC);
        var name = "output".equals(field) ? "out" : "in";
        // trace-level bodies are re-written by every root span of a merged session, so each write needs
        // its own object; span-level bodies are keyed by the stable span id and overwrite idempotently
        var owner = spanId == null || spanId.isBlank() ? "trace-" + UUID.randomUUID() : sanitize(spanId);
        var key = BODY_PREFIX + "/" + month + "/" + sanitize(traceId) + "/" + owner + "." + name + ".json.gz";
        return prefix == null ? key : prefix + "/" + key;
    }

    private String reference(String key, long rawBytes, long compressedBytes, String sha256, String preview) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put(REF_KEY, REF_SCHEME + key);
        ref.put("encoding", "gzip");
        ref.put("rawBytes", rawBytes);
        ref.put("compressedBytes", compressedBytes);
        ref.put("sha256", sha256);
        if (preview != null && !preview.isBlank()) ref.put("preview", preview);
        return JsonUtil.toJson(ref);
    }

    private String previewOf(String field, String body) {
        String preview;
        if (!"output".equals(field) && !"out".equals(field)) {
            preview = TracePreviewExtractor.extract(body);
        } else {
            var head = body.length() > OUTPUT_PREVIEW_CHARS ? body.substring(0, OUTPUT_PREVIEW_CHARS) : body;
            preview = head.replaceAll("\\s+", " ").strip();
        }
        return preview == null || preview.isBlank() ? null : preview;
    }

    private String monthOf(String key) {
        var index = key.indexOf(BODY_PREFIX + "/");
        if (index < 0) return null;
        var start = index + BODY_PREFIX.length() + 1;
        if (key.length() < start + 7) return null;
        var month = key.substring(start, start + 7);
        return month.matches("\\d{4}-\\d{2}") ? month : null;
    }
}
