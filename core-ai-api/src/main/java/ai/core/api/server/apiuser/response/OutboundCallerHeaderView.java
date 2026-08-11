package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;

/**
 * One outbound caller header mapping: header name + caller attribute source.
 *
 * @author stephen
 */
public class OutboundCallerHeaderView {
    @Property(name = "header_name")
    public String headerName;

    @Property(name = "value_source")
    public String valueSource;
}
