package ai.core.server.seoops.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author xander
 */
public enum SeoTaskStatus {
    @MongoEnumValue("DRAFT")
    DRAFT,
    @MongoEnumValue("NEEDS_INPUT")
    NEEDS_INPUT,
    @MongoEnumValue("BLOCKED")
    BLOCKED,
    @MongoEnumValue("READY_FOR_APPROVAL")
    READY_FOR_APPROVAL,
    @MongoEnumValue("APPROVED")
    APPROVED,
    @MongoEnumValue("REVISION_REQUIRED")
    REVISION_REQUIRED,
    @MongoEnumValue("APPROVAL_REVOKED")
    APPROVAL_REVOKED
}
