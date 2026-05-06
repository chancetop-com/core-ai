package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class Part {
    public static Part text(String text) {
        var part = new Part();
        part.text = text;
        part.mediaType = "text/plain";
        return part;
    }

    public static Part file(String name, String mimeType, String uri) {
        var part = new Part();
        part.filename = name;
        part.mediaType = mimeType;
        part.url = uri;
        return part;
    }

    public static Part raw(String name, String mimeType, String bytes) {
        var part = new Part();
        part.filename = name;
        part.mediaType = mimeType;
        part.raw = bytes;
        return part;
    }

    public static Part data(Object data) {
        var part = new Part();
        part.data = data;
        part.mediaType = "application/json";
        return part;
    }

    @Property(name = "text")
    public String text;

    @Property(name = "raw")
    public String raw;

    @Property(name = "url")
    public String url;

    @Property(name = "data")
    public Object data;

    @Property(name = "filename")
    public String filename;

    @Property(name = "mediaType")
    public String mediaType;

    @Property(name = "metadata")
    public Map<String, Object> metadata;
}
