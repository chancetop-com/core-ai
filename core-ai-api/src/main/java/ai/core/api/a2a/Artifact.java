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
        artifact.artifactId = "output";
        artifact.name = "output";
        artifact.parts = List.of(Part.text(text));
        return artifact;
    }

    @Property(name = "artifactId")
    public String artifactId;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "parts")
    public List<Part> parts;

    @Property(name = "metadata")
    public Map<String, Object> metadata;

    @Property(name = "extensions")
    public List<String> extensions;
}
