package ai.core.server.seoops.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author xander
 */
public enum SeoApprovalAction {
    @MongoEnumValue("APPROVE")
    APPROVE,
    @MongoEnumValue("REJECT")
    REJECT,
    @MongoEnumValue("REVOKE")
    REVOKE
}
