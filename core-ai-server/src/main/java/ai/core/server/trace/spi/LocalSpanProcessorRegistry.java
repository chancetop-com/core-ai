package ai.core.server.trace.spi;

import ai.core.server.trace.service.MemoryTraceSpanBuffer;
import ai.core.server.trace.service.OTLPIngestService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author stephen
 */
@SuppressFBWarnings({"MS_EXPOSE_REP", "EI_EXPOSE_STATIC_REP2"})
public final class LocalSpanProcessorRegistry {
    private static OTLPIngestService ingestService;
    private static MemoryTraceSpanBuffer buffer;

    public static void register(OTLPIngestService service) {
        ingestService = service;
    }

    public static OTLPIngestService getIngestService() {
        return ingestService;
    }

    public static void registerBuffer(MemoryTraceSpanBuffer traceBuffer) {
        buffer = traceBuffer;
    }

    public static MemoryTraceSpanBuffer getBuffer() {
        return buffer;
    }

    public static void clear() {
        ingestService = null;
        buffer = null;
    }

    private LocalSpanProcessorRegistry() {
    }
}
