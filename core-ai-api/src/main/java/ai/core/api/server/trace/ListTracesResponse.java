package ai.core.api.server.trace;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListTracesResponse {
    @Property(name = "traces")
    public List<TraceView> traces;

    @Property(name = "total")
    public Long total;
}
