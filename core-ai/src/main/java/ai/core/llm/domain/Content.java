package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class Content {
    public static Content of(String text) {
        var content = new Content();
        content.type = ContentType.TEXT;
        content.text = text == null ? "" : text;
        return content;
    }

    public static Content of(ImageUrl imageUrl) {
        var content = new Content();
        content.type = ContentType.IMAGE_URL;
        content.imageUrl = imageUrl;
        return content;
    }

    @NotNull
    @Property(name = "type")
    public ContentType type;

    @Property(name = "text")
    public String text;

    @Property(name = "image_url")
    public ImageUrl imageUrl;

    public enum ContentType {
        @Property(name = "text")
        TEXT,
        @Property(name = "image_url")
        IMAGE_URL
        // todo: support more content types
//        VIDEO,
//        AUDIO,
//        PDF
    }

    public static class ImageUrl {
        public static ImageUrl of(String url, String format) {
            var imageUrl = new ImageUrl();
            imageUrl.url = url;
            imageUrl.format = format;
            return imageUrl;
        }

        @Property(name = "url")
        public String url;

        @Property(name = "format")
        public String format;
    }
}
