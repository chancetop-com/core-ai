package ai.core.api.server.trace;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListSpansResponse {
    @Property(name = "spans")
    public List<SpanView> spans;
}
