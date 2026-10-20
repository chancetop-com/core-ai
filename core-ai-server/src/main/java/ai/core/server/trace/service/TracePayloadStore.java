package ai.core.server.trace.service;

import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TracePayloadBlob;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stores inline image data URLs embedded in trace payloads once per trace and replaces
 * them with a stable reference. The original string is kept verbatim, so hydrating a
 * payload restores it exactly; the only goal is removing repeated copies of the same image.
 *
 * @author stephen
 */
public class TracePayloadStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(TracePayloadStore.class);
    static final String REF_PREFIX = "coreai-image://";
    // A complete data URL inside a JSON/plain-text value, ending at a quote, parenthesis or whitespace.
    private static final Pattern IMAGE_DATA_URL = Pattern.compile("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]++(?=[\"')\\s]|$)");
    private static final Pattern IMAGE_REF = Pattern.compile("coreai-image://([0-9a-f]{64})");
    private static final int MAX_DATA_URL_LENGTH = 12 * 1024 * 1024;

    private static int mapCapacity(int expectedSize) {
        return expectedSize < 1 ? 1 : (int) (expectedSize / 0.75f) + 1;
    }

    private static Set<String> refs(String payload) {
        Set<String> hashes = new LinkedHashSet<>();
        var matcher = IMAGE_REF.matcher(payload);
        while (matcher.find()) {
            hashes.add(matcher.group(1));
        }
        return hashes;
    }

    private static void collectRefs(String traceId, String payload, Map<String, Set<String>> refsByTrace) {
        if (traceId == null || payload == null || !payload.contains(REF_PREFIX)) return;
        refsByTrace.computeIfAbsent(traceId, key -> new LinkedHashSet<>()).addAll(refs(payload));
    }

    private static String replaceRefs(String payload, Map<String, String> originals, boolean strict, String traceId) {
        if (payload == null || !payload.contains(REF_PREFIX)) return payload;
        var matcher = IMAGE_REF.matcher(payload);
        StringBuilder result = null;
        int lastEnd = 0;
        while (matcher.find()) {
            var original = originals.get(matcher.group(1));
            if (original == null) {
                if (strict) {
                    throw new IllegalStateException("trace payload blob missing: traceId=" + traceId + ", sha256=" + matcher.group(1));
                }
                continue;
            }
            if (result == null) result = new StringBuilder(payload.length());
            result.append(payload, lastEnd, matcher.start()).append(original);
            lastEnd = matcher.end();
        }
        if (result == null) return payload;
        result.append(payload, lastEnd, payload.length());
        return result.toString();
    }

    private static String contentType(String dataUrl) {
        int colon = dataUrl.indexOf(':');
        int semicolon = dataUrl.indexOf(';', colon + 1);
        return semicolon > colon ? dataUrl.substring(colon + 1, semicolon) : null;
    }

    private static long decodedSize(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return 0;
        long chars = dataUrl.length() - comma - 1L;
        long bytes = chars * 3 / 4;
        if (dataUrl.endsWith("==")) {
            bytes -= 2;
        } else if (dataUrl.endsWith("=")) {
            bytes -= 1;
        }
        return Math.max(bytes, 0);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Inject
    MongoCollection<TracePayloadBlob> blobCollection;

    public Compactor newCompactor() {
        return new Compactor();
    }

    public void hydrate(Trace trace, boolean strict) {
        if (trace == null) return;
        trace.input = hydrate(trace.traceId, trace.input, strict);
        trace.output = hydrate(trace.traceId, trace.output, strict);
    }

    public void hydrate(Span span, boolean strict) {
        if (span == null) return;
        span.input = hydrate(span.traceId, span.input, strict);
        span.output = hydrate(span.traceId, span.output, strict);
    }

    public void hydrate(List<Span> spans, boolean strict) {
        if (spans == null || spans.isEmpty()) return;

        Map<String, Set<String>> refsByTrace = new HashMap<>(mapCapacity(spans.size()));
        for (var span : spans) {
            collectRefs(span.traceId, span.input, refsByTrace);
            collectRefs(span.traceId, span.output, refsByTrace);
        }
        if (refsByTrace.isEmpty()) return;

        Map<String, Map<String, String>> originalsByTrace = new HashMap<>(mapCapacity(refsByTrace.size()));
        for (var entry : refsByTrace.entrySet()) {
            originalsByTrace.put(entry.getKey(), load(entry.getKey(), entry.getValue(), strict));
        }
        for (var span : spans) {
            var originals = originalsByTrace.get(span.traceId);
            if (originals == null) continue;
            span.input = replaceRefs(span.input, originals, strict, span.traceId);
            span.output = replaceRefs(span.output, originals, strict, span.traceId);
        }
    }

    private String hydrate(String traceId, String payload, boolean strict) {
        if (payload == null || traceId == null || !payload.contains(REF_PREFIX)) return payload;
        var hashes = refs(payload);
        if (hashes.isEmpty()) return payload;
        var originals = load(traceId, hashes, strict);
        return replaceRefs(payload, originals, strict, traceId);
    }

    public void deleteForTraces(List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return;
        long deleted = 0;
        for (int i = 0; i < traceIds.size(); i += 500) {
            var batch = traceIds.subList(i, Math.min(i + 500, traceIds.size()));
            deleted += blobCollection.delete(Filters.in("trace_id", batch));
        }
        if (deleted > 0) {
            LOGGER.info("deleted {} trace payload blobs for {} traces", deleted, traceIds.size());
        }
    }

    private Map<String, String> load(String traceId, Set<String> hashes, boolean strict) {
        var ids = new ArrayList<String>(hashes.size());
        for (var hash : hashes) {
            ids.add(traceId + ':' + hash);
        }
        var query = new Query();
        query.filter = Filters.in("_id", ids);
        query.limit = ids.size();

        Map<String, String> originals = new HashMap<>(mapCapacity(hashes.size()));
        for (var blob : blobCollection.find(query)) {
            originals.put(blob.id.substring(traceId.length() + 1), blob.data);
        }
        if (strict) {
            for (var hash : hashes) {
                if (!originals.containsKey(hash)) {
                    throw new IllegalStateException("trace payload blob missing: traceId=" + traceId + ", sha256=" + hash);
                }
            }
        } else if (originals.size() < hashes.size()) {
            LOGGER.warn("trace payload blob missing, keeping reference, traceId={}, missing={}",
                    traceId, hashes.size() - originals.size());
        }
        return originals;
    }

    private boolean store(String key, String traceId, String dataUrl) {
        var blob = new TracePayloadBlob();
        blob.id = key;
        blob.traceId = traceId;
        blob.data = dataUrl;
        blob.contentType = contentType(dataUrl);
        blob.sizeBytes = decodedSize(dataUrl);
        blob.createdAt = ZonedDateTime.now();
        try {
            blobCollection.insert(blob);
            return true;
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) return true; // already stored by an earlier span of the same trace
            LOGGER.warn("failed to store trace payload blob, keeping payload inline, traceId={}", traceId, e);
            return false;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to store trace payload blob, keeping payload inline, traceId={}", traceId, e);
            return false;
        }
    }

    public final class Compactor {
        private final Map<String, String> stored = new HashMap<>();

        public String compact(String traceId, String payload) {
            if (payload == null || traceId == null || payload.length() < 32 || !payload.contains("data:image/")) return payload;

            var matcher = IMAGE_DATA_URL.matcher(payload);
            StringBuilder result = null;
            int lastEnd = 0;
            while (matcher.find()) {
                var dataUrl = matcher.group();
                if (dataUrl.length() > MAX_DATA_URL_LENGTH) {
                    LOGGER.debug("inline image too large to deduplicate, traceId={}, chars={}", traceId, dataUrl.length());
                    continue;
                }
                var hash = sha256(dataUrl);
                var key = traceId + ':' + hash;
                var ref = stored.get(key);
                if (ref == null) {
                    if (!store(key, traceId, dataUrl)) continue;
                    ref = REF_PREFIX + hash;
                    stored.put(key, ref);
                }
                if (result == null) result = new StringBuilder(payload.length());
                result.append(payload, lastEnd, matcher.start()).append(ref);
                lastEnd = matcher.end();
            }
            if (result == null) return payload;
            result.append(payload, lastEnd, payload.length());
            return result.toString();
        }
    }
}
