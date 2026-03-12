package ai.core.api.server.run;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class LLMCallRequest {
    @NotNull
    @NotBlank
    @Property(name = "input")
    public String input;

    @Property(name = "attachments")
    public List<Attachment> attachments;

    public static class Attachment {
        @NotNull
        @NotBlank
        @Property(name = "url")
        public String url;

        @NotNull
        @NotBlank
        @Property(name = "type")
        public String type;
    }
}
