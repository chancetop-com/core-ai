package ai.core.server.trace.web.ingest;

import java.util.List;

/**
 * @author Xander
 */
public class IngestRequest {
    public String serviceName;
    public String serviceVersion;
    public String environment;
    public List<IngestSpanRequest> spans;
}
