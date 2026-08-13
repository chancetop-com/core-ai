package ai.core.api.server.prompt;

import core.framework.api.json.Property;

/**
 * JSON view mirror of the prompt template status enum. Separate from the entity enum
 * because core-ng forbids @MongoEnumValue and @Property on the same class.
 *
 * @author stephen
 */
public enum PromptStatusView {
    @Property(name = "DRAFT")
    DRAFT,
    @Property(name = "PUBLISHED")
    PUBLISHED,
    @Property(name = "ARCHIVED")
    ARCHIVED
}
