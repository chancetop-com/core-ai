package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * Dataset usage type.
 * GENERAL: records produced by agent runs, accessed via query/insert/update/delete dataset tools.
 * SESSION: per-session state documents, accessed via get_session_state/set_session_state tools.
 *
 * @author stephen
 */
public enum DatasetType {
    @MongoEnumValue("GENERAL")
    GENERAL,
    @MongoEnumValue("SESSION")
    SESSION
}
