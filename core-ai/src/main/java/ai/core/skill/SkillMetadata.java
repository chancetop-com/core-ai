package ai.core.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author xander
 */
public final class SkillMetadata {
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final int MAX_SKILL_NAME_LENGTH = 64;

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_SKILL_NAME_LENGTH) return false;
        return SKILL_NAME_PATTERN.matcher(name).matches();
    }

    public static Builder builder(String name, String description, String path) {
        return new Builder(name, description, path);
    }

    private final String name;
    private final String description;
    private final String path;
    private final String skillDir;
    private final String license;
    private final String compatibility;
    private final Map<String, String> metadata;
    private final List<String> allowedTools;
    private final List<String> resources;

    private SkillMetadata(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.path = builder.path;
        this.skillDir = builder.skillDir;
        this.license = builder.license;
        this.compatibility = builder.compatibility;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
        this.allowedTools = List.copyOf(builder.allowedTools);
        this.resources = List.copyOf(builder.resources);
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

    public String getSkillDir() {
        return skillDir;
    }

    public List<String> getResources() {
        return resources;
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
        private String skillDir;
        private String license;
        private String compatibility;
        private Map<String, String> metadata = Collections.emptyMap();
        private List<String> allowedTools = Collections.emptyList();
        private List<String> resources = Collections.emptyList();

        private Builder(String name, String description, String path) {
            this.name = name;
            this.description = description;
            this.path = path;
        }

        public Builder skillDir(String skillDir) {
            this.skillDir = skillDir;
            return this;
        }

        public Builder resources(List<String> resources) {
            this.resources = resources != null ? resources : Collections.emptyList();
            return this;
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
