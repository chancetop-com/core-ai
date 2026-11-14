package ai.core.server.trace.service;

import ai.core.server.trace.domain.TracePayloadBlob;

import java.util.List;

/**
 * Persistence for the deduplicated inline payloads referenced as {@code coreai-image://<sha256>}.
 * Blobs are scoped by trace id, so they can be deleted together with the trace.
 *
 * @author stephen
 */
public interface TracePayloadRepository {
    /**
     * Stores the blob unless this trace already has the same content hash.
     *
     * @return true when it is present after the call
     */
    boolean store(TracePayloadBlob blob);

    List<TracePayloadBlob> findByTrace(String traceId, List<String> blobHashes);

    long deleteByTraceIds(List<String> traceIds);
}
