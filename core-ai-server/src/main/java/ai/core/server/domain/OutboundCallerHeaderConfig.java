package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * One outbound caller header mapping: header name + caller attribute source.
 * Embedded in SystemSettings; not a standalone collection.
 *
 * @author stephen
 */
public class OutboundCallerHeaderConfig {
    @Field(name = "header_name")
    public String headerName;

    @Field(name = "value_source")
    public String valueSource;
}
