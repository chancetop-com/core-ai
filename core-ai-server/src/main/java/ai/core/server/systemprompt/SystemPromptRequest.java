package ai.core.server.systemprompt;

import java.util.List;

/**
 * @author Xander
 */
public class SystemPromptRequest {
    public String name;
    public String description;
    public String content;
    public List<String> tags;
    public String changelog;
}
