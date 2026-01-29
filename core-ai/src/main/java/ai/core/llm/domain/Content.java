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

    public static Content ofFileUrl(String url) {
        var content = new Content();
        content.type = ContentType.FILE;
        content.file = FileContent.ofUrl(url);
        return content;
    }

    public static Content ofFileBase64(String data, String mediaType, String filename) {
        var content = new Content();
        content.type = ContentType.FILE;
        content.file = FileContent.ofBase64(data, mediaType, filename);
        return content;
    }

    @NotNull
    @Property(name = "type")
    public ContentType type;

    @Property(name = "text")
    public String text;

    @Property(name = "image_url")
    public ImageUrl imageUrl;

    @Property(name = "file")
    public FileContent file;

    public enum ContentType {
        @Property(name = "text")
        TEXT,
        @Property(name = "image_url")
        IMAGE_URL,
        @Property(name = "file")
        FILE
        // todo: support more content types
//        VIDEO,
//        AUDIO
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

        // can be null if not base64 encoded
        @Property(name = "format")
        public String format;
    }

    public static class FileContent {
        public static FileContent ofUrl(String url) {
            var file = new FileContent();
            file.fileId = url;
            return file;
        }

        public static FileContent ofBase64(String data, String mediaType, String filename) {
            var file = new FileContent();
            file.filename = filename;
            file.fileData = String.format("data:%s;base64,%s", mediaType, data);
            return file;
        }

        @Property(name = "file_id")
        public String fileId;

        @Property(name = "filename")
        public String filename;

        @Property(name = "file_data")
        public String fileData;
    }
}
