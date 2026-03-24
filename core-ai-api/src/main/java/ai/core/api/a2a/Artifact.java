package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class Artifact {
    public static Artifact text(String text) {
        var artifact = new Artifact();
        artifact.parts = List.of(Part.text(text));
        return artifact;
    }

    @Property(name = "name")
    public String name;

    @Property(name = "parts")
    public List<Part> parts;

    @Property(name = "metadata")
    public Map<String, String> metadata;
}
