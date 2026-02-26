package ai.core.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author xander
 */
public final class SkillMetadata {
    public static Builder builder(String name, String description, String path) {
        return new Builder(name, description, path);
    }

    private final String name;
    private final String description;
    private final String path;
    private final String license;
    private final String compatibility;
    private final Map<String, String> metadata;
    private final List<String> allowedTools;

    private SkillMetadata(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.path = builder.path;
        this.license = builder.license;
        this.compatibility = builder.compatibility;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
        this.allowedTools = Collections.unmodifiableList(new ArrayList<>(builder.allowedTools));
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getPath() {
        return path;
    }

    public String getLicense() {
        return license;
    }

    public String getCompatibility() {
        return compatibility;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final String path;
        private String license;
        private String compatibility;
        private Map<String, String> metadata = Collections.emptyMap();
        private List<String> allowedTools = Collections.emptyList();

        private Builder(String name, String description, String path) {
            this.name = name;
            this.description = description;
            this.path = path;
        }

        public Builder license(String license) {
            this.license = license;
            return this;
        }

        public Builder compatibility(String compatibility) {
            this.compatibility = compatibility;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata != null ? metadata : Collections.emptyMap();
            return this;
        }

        public Builder allowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools != null ? allowedTools : Collections.emptyList();
            return this;
        }

        public SkillMetadata build() {
            return new SkillMetadata(this);
        }
    }
}
