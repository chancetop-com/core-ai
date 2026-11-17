package ai.core.api.server.conversation;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author core-ai
 */
public class ConversationRenameRequest {
    @NotNull
    @NotBlank
    @Property(name = "title")
    public String title;
}
