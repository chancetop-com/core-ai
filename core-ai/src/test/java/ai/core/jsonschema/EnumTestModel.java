package ai.core.jsonschema;

import ai.core.api.tool.function.CoreAiParameter;
import core.framework.api.json.Property;

import java.util.List;

public class EnumTestModel {
    @CoreAiParameter(name = "tags", description = "tags")
    public List<Tag> tags;

    @CoreAiParameter(name = "status", description = "status")
    public Tag status;

    public enum Tag {
        @Property(name = "A")
        A,
        @Property(name = "B")
        B,
        @Property(name = "C")
        C
    }
}
