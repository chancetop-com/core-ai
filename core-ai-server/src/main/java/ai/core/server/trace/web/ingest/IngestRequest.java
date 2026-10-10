package ai.core.server.trace.web.ingest;

import java.util.List;

/**
 * @author Xander
 */
public class IngestRequest {
    public String serviceName;
    public String serviceVersion;
    public String environment;
    // Client-declared trace origin; absent means the "cli" default. Desktop app-server engines declare "desktop".
    public String clientType;
    public List<IngestSpanRequest> spans;
}
