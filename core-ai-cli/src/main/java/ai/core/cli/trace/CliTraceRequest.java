package ai.core.cli.trace;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Request payload mirroring server IngestRequest.
 *
 * <p>Fields carry {@link Property} annotations so the class is registered for reflection in the
 * GraalVM native image; without them JsonUtil.toJson fails at runtime with "No serializer found".
 *
 * @author Xander
 */
public class CliTraceRequest {
    @Property(name = "serviceName")
    public String serviceName;
    @Property(name = "serviceVersion")
    public String serviceVersion;
    @Property(name = "environment")
    public String environment;
    @Property(name = "spans")
    public List<CliTraceSpan> spans;
}
