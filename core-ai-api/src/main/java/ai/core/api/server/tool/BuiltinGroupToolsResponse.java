package ai.core.api.server.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class BuiltinGroupToolsResponse {
    @Property(name = "group_id")
    public String groupId;

    @Property(name = "group_name")
    public String groupName;

    @Property(name = "tools")
    public List<ToolInfo> tools;

    public static class ToolInfo {
        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "input_schema")
        public String inputSchema;
    }
}
