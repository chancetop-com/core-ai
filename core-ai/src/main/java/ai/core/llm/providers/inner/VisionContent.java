package ai.core.llm.providers.inner;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class VisionContent {
    @NotNull
    @Property(name = "type")
    public Type type;

    @Property(name = "text")
    public String text;

    @Property(name = "image_url")
    public ImageUrl imageUrl;

    public enum Type {
        @Property(name = "text")
        TEXT,
        @Property(name = "image_url")
        IMAGE_URL
    }

    public static class ImageUrl {
        @NotNull
        @Property(name = "url")
        public String url;
    }
}
