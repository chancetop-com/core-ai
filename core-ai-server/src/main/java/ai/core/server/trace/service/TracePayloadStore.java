package ai.core.server.trace.service;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TracePayloadBlob;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    /** One trace may keep at most this many decoded image bytes; beyond it the span keeps a placeholder. */
    private static final long MAX_TRACE_PAYLOAD_BYTES = 8L * 1024 * 1024;
    /** A detail response hydrates at most this many bytes; the rest stay as refs for the media endpoint. */
    private static final long MAX_HYDRATE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_DEDUP_ENTRIES = 4096;

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

    private static String replaceRefs(String payload, Map<String, String> originals, boolean strict, String traceId,
                                      long maxBytes) {
        if (payload == null || !payload.contains(REF_PREFIX)) return payload;
        var matcher = IMAGE_REF.matcher(payload);
        StringBuilder result = null;
        int lastEnd = 0;
        long hydratedBytes = 0;
        while (matcher.find()) {
            var original = originals.get(matcher.group(1));
            if (original == null) {
                if (strict) {
                    throw new IllegalStateException("trace payload blob missing: traceId=" + traceId + ", sha256=" + matcher.group(1));
                }
                continue;
            }
            if (hydratedBytes + original.length() > maxBytes) {
                // keep the stable ref and let the viewer fetch the bytes through the media endpoint
                continue;
            }
            if (result == null) result = new StringBuilder(payload.length());
            result.append(payload, lastEnd, matcher.start()).append(original);
            lastEnd = matcher.end();
            hydratedBytes += original.length();
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

    private static String sha256(byte[] value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] decodeDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) throw new IllegalArgumentException("invalid data URL");
        return Base64.getMimeDecoder().decode(dataUrl.substring(comma + 1));
    }

    @Inject
    ObjectStorageServiceResolver objectStorageResolver;
    @Inject
    TracePayloadRepository payloadRepository;

    private final Map<String, Boolean> uploadedObjects = new LinkedHashMap<>();
    private String storageMode = "db";
    private String configuredContainer;
    private String objectPrefix;
    private boolean objectStorageAllowed;
    private boolean backfillEnabled;
    private int backfillBatchSize = 500;

    /**
     * Configures the storage mode selected by {@code sys.trace.payload.storage}. {@code auto} still
     * falls back to inline data when no object storage is configured; {@code object} requires it.
     */
    public void configure(String mode, String container, String prefix, boolean pgTraceStore) {
        storageMode = mode == null || mode.isBlank() ? "db" : mode;
        if (!pgTraceStore && "object".equals(storageMode)) {
            throw new IllegalArgumentException("sys.trace.payload.storage=object requires the pg trace store");
        }
        objectStorageAllowed = pgTraceStore;
        configuredContainer = container == null || container.isBlank() ? null : container;
        objectPrefix = prefix == null || prefix.isBlank() ? null : prefix;
    }

    public void configureBackfill(boolean enabled, int batchSize) {
        backfillEnabled = enabled;
        if (batchSize > 0) backfillBatchSize = batchSize;
    }

    public boolean backfillEnabled() {
        return backfillEnabled;
    }

    public int backfillBatch(int limit) {
        var service = objectStorage();
        if (service == null) return 0;
        int updated = 0;
        for (var blob : payloadRepository.findBackfillCandidates(limit > 0 ? limit : backfillBatchSize)) {
            if (blob.data == null || blob.storageKey != null) continue;
            try {
                blob.contentType = contentType(blob.data);
                uploadObject(blob, blob.data, service);
                if (payloadRepository.updateBackfillReference(blob) > 0) {
                    blob.data = null;
                    updated++;
                }
            } catch (RuntimeException e) {
                LOGGER.warn("trace payload backfill failed, traceId={}, blob={}", blob.traceId, blob.id, e);
                payloadRepository.markBackfillFailure(blob, e.getMessage());
            }
        }
        return updated;
    }

    private ObjectStorageService objectStorage() {
        if (!objectStorageAllowed || "db".equals(storageMode)) return null;
        var service = objectStorageResolver != null ? objectStorageResolver.resolve() : null;
        if (service == null && "object".equals(storageMode)) {
            throw new IllegalStateException("sys.trace.payload.storage=object but object storage is not configured");
        }
        return service;
    }

    private String storageContainer() {
        return configuredContainer != null ? configuredContainer : objectStorageResolver.artifactContainer();
    }

    private String storageKey(String objectHash) {
        var month = YearMonth.now(ZoneOffset.UTC);
        var key = "trace-blobs/" + month + "/" + objectHash.substring(0, 2) + "/" + objectHash;
        return objectPrefix == null ? key : objectPrefix + "/" + key;
    }

    private boolean uploaded(String key) {
        synchronized (uploadedObjects) {
            return uploadedObjects.containsKey(key);
        }
    }

    private void markUploaded(String key) {
        synchronized (uploadedObjects) {
            if (uploadedObjects.size() >= MAX_DEDUP_ENTRIES) {
                Iterator<String> iterator = uploadedObjects.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            uploadedObjects.put(key, Boolean.TRUE);
        }
    }

    private String loadPayload(TracePayloadBlob blob) {
        if (blob.storageKey == null) return blob.data;
        var service = objectStorage();
        if (service == null) throw new IllegalStateException("object storage is not configured");
        byte[] bytes = service.downloadObject(storageContainer(), blob.storageKey);
        var contentType = blob.contentType != null ? blob.contentType : "application/octet-stream";
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    public Compactor newCompactor() {
        return new Compactor();
    }

    public void hydrate(Trace trace, boolean strict) {
        if (trace == null) return;
        var maxBytes = strict ? Long.MAX_VALUE : MAX_HYDRATE_BYTES;
        trace.input = hydrate(trace.traceId, trace.input, strict, maxBytes);
        trace.output = hydrate(trace.traceId, trace.output, strict, maxBytes);
    }

    public void hydrate(Span span, boolean strict) {
        if (span == null) return;
        var maxBytes = strict ? Long.MAX_VALUE : MAX_HYDRATE_BYTES;
        span.input = hydrate(span.traceId, span.input, strict, maxBytes);
        span.output = hydrate(span.traceId, span.output, strict, maxBytes);
    }

    public void hydrate(List<Span> spans, boolean strict) {
        if (spans == null || spans.isEmpty()) return;
        var maxBytes = strict ? Long.MAX_VALUE : MAX_HYDRATE_BYTES;

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
            span.input = replaceRefs(span.input, originals, strict, span.traceId, maxBytes);
            span.output = replaceRefs(span.output, originals, strict, span.traceId, maxBytes);
        }
    }

    private String hydrate(String traceId, String payload, boolean strict, long maxBytes) {
        if (payload == null || traceId == null || !payload.contains(REF_PREFIX)) return payload;
        var hashes = refs(payload);
        if (hashes.isEmpty()) return payload;
        var originals = load(traceId, hashes, strict);
        return replaceRefs(payload, originals, strict, traceId, maxBytes);
    }

    /**
     * Stores one data URL supplied by an uploading client (for example a CLI that still owns the
     * image locally) under this trace, so the viewer can fetch it later.
     *
     * @return the sha256 the payload is addressed by, or null when it could not be stored
     */
    public String storePayload(String traceId, String dataUrl) {
        if (traceId == null || dataUrl == null || !dataUrl.startsWith("data:")) return null;
        if (dataUrl.length() > MAX_DATA_URL_LENGTH) return null;
        var hash = sha256(dataUrl);
        return store(traceId + ':' + hash, traceId, dataUrl) ? hash : null;
    }

    /** @return the decoded payload bytes, or null when the trace does not own that hash */
    public Payload payload(String traceId, String sha256) {
        if (traceId == null || sha256 == null) return null;
        var blobs = payloadRepository.findByTrace(traceId, List.of(sha256));
        if (blobs.isEmpty()) return null;
        var blob = blobs.getFirst();
        if (blob.storageKey != null) {
            var service = objectStorage();
            if (service == null) throw new IllegalStateException("object storage is not configured");
            return new Payload(service.downloadObject(storageContainer(), blob.storageKey), blob.contentType);
        }
        var dataUrl = blob.data;
        var comma = dataUrl.indexOf(',');
        if (comma < 0) return null;
        return new Payload(Base64.getDecoder().decode(dataUrl.substring(comma + 1)), contentType(dataUrl));
    }

    public List<String> deleteForTraces(List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return List.of();
        var keys = payloadRepository.deleteByTraceIdsReturningStorageKeys(traceIds);
        if (!keys.isEmpty()) {
            LOGGER.info("deleted trace payload rows for {} traces, storage keys={}", traceIds.size(), keys.size());
        }
        return keys;
    }

    /** Deletes shared CAS objects only when no remaining trace payload row references them. */
    public void deleteObjects(List<String> storageKeys) {
        if (storageKeys == null || storageKeys.isEmpty()) return;
        var service = objectStorage();
        if (service == null) return;
        for (var key : storageKeys) {
            if (payloadRepository.hasStorageKey(key)) continue;
            try {
                service.deleteObject(storageContainer(), key);
            } catch (RuntimeException e) {
                LOGGER.warn("failed to delete trace payload object, key={}", key, e);
            }
        }
    }

    private Map<String, String> load(String traceId, Set<String> hashes, boolean strict) {
        Map<String, String> originals = new HashMap<>(mapCapacity(hashes.size()));
        for (var blob : payloadRepository.findByTrace(traceId, new ArrayList<>(hashes))) {
            originals.put(blob.id.substring(traceId.length() + 1), loadPayload(blob));
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

    private void uploadObject(TracePayloadBlob blob, String dataUrl, ObjectStorageService service) {
        var bytes = decodeDataUrl(dataUrl);
        blob.objectHash = sha256(bytes);
        blob.storageKey = storageKey(blob.objectHash);
        blob.sizeBytes = (long) bytes.length;
        if (!uploaded(blob.storageKey)) {
            service.uploadObject(storageContainer(), blob.storageKey, bytes, blob.contentType);
            markUploaded(blob.storageKey);
        }
    }

    private boolean store(String key, String traceId, String dataUrl) {
        var blob = new TracePayloadBlob();
        blob.id = key;
        blob.traceId = traceId;
        blob.contentType = contentType(dataUrl);
        var service = objectStorage();
        if (service != null) {
            uploadObject(blob, dataUrl, service);
        } else {
            blob.data = dataUrl;
            blob.sizeBytes = decodedSize(dataUrl);
        }
        blob.createdAt = ZonedDateTime.now();
        return payloadRepository.store(blob);
    }

    public final class Compactor {
        private final Map<String, String> stored = new HashMap<>();
        private final Map<String, Long> storedBytes = new HashMap<>();

        public String compact(String traceId, String payload) {
            if (payload == null || traceId == null || payload.length() < 32 || !payload.contains("data:image/")) return payload;

            var matcher = IMAGE_DATA_URL.matcher(payload);
            StringBuilder result = null;
            int lastEnd = 0;
            while (matcher.find()) {
                var dataUrl = matcher.group();
                var bytes = decodedSize(dataUrl);
                var omitted = dataUrl.length() > MAX_DATA_URL_LENGTH
                        || storedBytes.getOrDefault(traceId, 0L) + bytes > MAX_TRACE_PAYLOAD_BYTES;
                var hash = omitted ? null : sha256(dataUrl);
                var key = traceId + ':' + hash;
                var ref = omitted ? null : stored.get(key);
                if (!omitted && ref == null) {
                    if (!store(key, traceId, dataUrl)) {
                        omitted = true;
                    } else {
                        ref = REF_PREFIX + hash;
                        stored.put(key, ref);
                        storedBytes.merge(traceId, bytes, Long::sum);
                    }
                }
                if (result == null) result = new StringBuilder(payload.length());
                result.append(payload, lastEnd, matcher.start())
                        .append(omitted ? "[image omitted from trace: " + bytes + " bytes]" : ref);
                lastEnd = matcher.end();
            }
            if (result == null) return payload;
            result.append(payload, lastEnd, payload.length());
            return result.toString();
        }
    }

    /** One stored payload as the media endpoint serves it. */
    public record Payload(byte[] bytes, String contentType) {
    }
}
