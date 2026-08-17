package ai.core.server.seoops.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author xander
 */
public enum SeoEvidenceState {
    @MongoEnumValue("NONE")
    NONE,
    @MongoEnumValue("PARTIAL")
    PARTIAL,
    @MongoEnumValue("VERIFIED")
    VERIFIED,
    @MongoEnumValue("UNVERIFIABLE")
    UNVERIFIABLE
}
