package ai.core.server.seoops.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author xander
 */
public enum SeoEvidenceVerification {
    @MongoEnumValue("UNVERIFIED")
    UNVERIFIED,
    @MongoEnumValue("VERIFIED")
    VERIFIED,
    @MongoEnumValue("UNVERIFIABLE")
    UNVERIFIABLE
}
