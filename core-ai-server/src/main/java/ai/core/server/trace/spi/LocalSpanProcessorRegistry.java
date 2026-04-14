package ai.core.server.trace.spi;

import ai.core.server.trace.service.OTLPIngestService;

/**
 * @author stephen
 */
public final class LocalSpanProcessorRegistry {
    private static OTLPIngestService ingestService;

    public static void register(OTLPIngestService service) {
        ingestService = service;
    }

    public static OTLPIngestService getIngestService() {
        return ingestService;
    }

    public static void clear() {
        ingestService = null;
    }

    private LocalSpanProcessorRegistry() {
    }
}
