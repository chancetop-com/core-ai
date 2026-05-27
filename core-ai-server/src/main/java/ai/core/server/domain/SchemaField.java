package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * @author stephen
 */
public class SchemaField {
    @Field(name = "name")
    public String name;

    @Field(name = "type")
    public SchemaFieldType type;

    @Field(name = "label")
    public String label;
}
