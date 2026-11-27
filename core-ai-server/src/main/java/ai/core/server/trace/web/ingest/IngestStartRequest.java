package ai.core.server.trace.web.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * @author stephen
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IngestStartRequest {
    public String traceId;
    public String name;
    public String input;
    public Long startedAtEpochMs;
    public String serviceName;
    public String serviceVersion;
    public String environment;
    public String clientType;
}
