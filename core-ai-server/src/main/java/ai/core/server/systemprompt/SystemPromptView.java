package ai.core.server.systemprompt;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author Xander
 */
public class SystemPromptView {
    public String id;
    public String promptId;
    public String name;
    public String description;
    public String content;
    public List<String> variables;
    public Integer version;
    public String changelog;
    public List<String> tags;
    public String userId;
    public ZonedDateTime createdAt;
}
