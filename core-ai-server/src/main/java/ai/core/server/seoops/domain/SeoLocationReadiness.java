package ai.core.server.seoops.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author xander
 */
public enum SeoLocationReadiness {
    @MongoEnumValue("READY")
    READY,
    @MongoEnumValue("BLOCKED")
    BLOCKED,
    @MongoEnumValue("INCOMPLETE")
    INCOMPLETE
}
