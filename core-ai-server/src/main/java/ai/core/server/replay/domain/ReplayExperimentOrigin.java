package ai.core.server.replay.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * How a replay experiment was created: SPAN experiments snapshot an existing
 * trace LLM span, BLANK (playground) experiments start from an empty request.
 *
 * @author stephen
 */
public enum ReplayExperimentOrigin {
    @MongoEnumValue("span")
    SPAN,
    @MongoEnumValue("blank")
    BLANK
}
