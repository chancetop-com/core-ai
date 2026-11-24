package ai.core.server.trace.web.ingest;

/**
 * One image a client still owns locally, uploaded with its trace so the server can serve it later.
 * The server recomputes the content hash; a client-supplied hash is never trusted.
 *
 * @author core-ai
 */
public class IngestPayloadRequest {
    public String traceId;
    public String dataUrl;
}
