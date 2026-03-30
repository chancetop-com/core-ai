package ai.core.api.server.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListApiAppsResponse {
    @Property(name = "apps")
    public List<ApiAppView> apps;

    public static class ApiAppView {
        @Property(name = "name")
        public String name;

        @Property(name = "base_url")
        public String baseUrl;

        @Property(name = "version")
        public String version;

        @Property(name = "description")
        public String description;
    }
}
