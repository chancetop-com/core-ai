package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class SendMessageRequest {
    @NotNull
    @Property(name = "message")
    public String message;

    @Property(name = "variables")
    public Map<String, String> variables;

    @Property(name = "attachments")
    public List<SendMessageAttachment> attachments;

    public static class SendMessageAttachment {
        @NotNull
        @Property(name = "url")
        public String url;

        @NotNull
        @Property(name = "type")
        public String type;

        @Property(name = "file_name")
        public String fileName;

        @Property(name = "category")
        public String category;

        @Property(name = "container")
        public String container;

        @Property(name = "blob_name")
        public String blobName;
    }
}
