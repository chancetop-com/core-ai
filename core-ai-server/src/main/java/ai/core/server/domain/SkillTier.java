package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * Governance tier for skills. Each tier owns an independent quality policy scope.
 *
 * @author stephen
 */
public enum SkillTier {
    @MongoEnumValue("IMPORTED")
    IMPORTED("skill:tier:imported"),

    @MongoEnumValue("DOMAIN")
    DOMAIN("skill:tier:domain"),

    @MongoEnumValue("CORE")
    CORE("skill:tier:core"),

    @MongoEnumValue("ALWAYS_ON")
    ALWAYS_ON("skill:tier:always-on");

    public static final String SCOPE_IMPORTED = "skill:tier:imported";
    public static final String SCOPE_DOMAIN = "skill:tier:domain";
    public static final String SCOPE_CORE = "skill:tier:core";
    public static final String SCOPE_ALWAYS_ON = "skill:tier:always-on";

    private final String scope;

    SkillTier(String scope) {
        this.scope = scope;
    }

    public String scope() {
        return scope;
    }
}
