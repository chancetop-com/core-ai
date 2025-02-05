package ai.core.litellm.completion;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class ImageMessageAJAXView {
    @NotNull
    @Property(name = "role")
    public RoleTypeAJAXView role;

    @Property(name = "content")
    public List<ImageContent> content;

    public enum Type {
        @Property(name = "text")
        TEXT,
        @Property(name = "image_url")
        IMAGE_URL
    }

    public static class ImageContent {
        @NotNull
        @Property(name = "type")
        public Type type;

        @Property(name = "text")
        public String text;

        @Property(name = "image_url")
        public ImageUrl imageUrl;
    }

    public static class ImageUrl {
        @NotNull
        @Property(name = "url")
        public String url;
    }
}
