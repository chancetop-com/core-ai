package ai.core.api.server.run;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class LLMCallRequest {
    @Property(name = "input")
    public String input;

    @Property(name = "attachments")
    public List<Attachment> attachments;

    public enum AttachmentType {
        @Property(name = "IMAGE")
        IMAGE,
        @Property(name = "PDF")
        PDF
    }

    public static class Attachment {
        @Property(name = "url")
        public String url;

        @NotNull
        @Property(name = "type")
        public AttachmentType type;

        @Property(name = "data")
        public String data;

        @Property(name = "media_type")
        public String mediaType;
    }
}
