package ai.core.server.quality.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author core-ai
 */
public enum QualityEvidenceKind {
    @MongoEnumValue("CODE_FACT")
    CODE_FACT,
    @MongoEnumValue("SEMANTIC_ASSESSMENT")
    SEMANTIC_ASSESSMENT
}
