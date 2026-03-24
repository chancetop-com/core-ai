package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class Part {
    public static Part text(String text) {
        var part = new Part();
        part.type = "text";
        part.text = text;
        return part;
    }

    public static Part file(String name, String mimeType, String uri) {
        var part = new Part();
        part.type = "file";
        var file = new FileContent();
        file.name = name;
        file.mimeType = mimeType;
        file.uri = uri;
        part.file = file;
        return part;
    }

    public static Part data(Map<String, Object> data) {
        var part = new Part();
        part.type = "data";
        part.data = data;
        return part;
    }

    @Property(name = "type")
    public String type;

    @Property(name = "text")
    public String text;

    @Property(name = "file")
    public FileContent file;

    @Property(name = "data")
    public Map<String, Object> data;

    @Property(name = "metadata")
    public Map<String, String> metadata;

    public static class FileContent {
        @Property(name = "name")
        public String name;

        @Property(name = "mimeType")
        public String mimeType;

        @Property(name = "uri")
        public String uri;
    }
}
