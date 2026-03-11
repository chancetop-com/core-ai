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

    public static final String TYPE_SYSTEM = "system";
    public static final String TYPE_TASK = "task";

    public static Builder builder(String name, String description, String path) {
        return new Builder(name, description, path);
    }

    private final String name;
    private final String description;
    private final String type;
    private final String path;
    private final String skillDir;
    private final String license;
    private final String compatibility;
    private final Map<String, String> metadata;
    private final List<String> allowedTools;
    private final List<String> triggers;
    private final List<ReferenceEntry> references;
    private final List<String> examples;
    private final String outputFormat;

    private SkillMetadata(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.type = builder.type;
        this.path = builder.path;
        this.skillDir = builder.skillDir;
        this.license = builder.license;
        this.compatibility = builder.compatibility;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
        this.allowedTools = Collections.unmodifiableList(new ArrayList<>(builder.allowedTools));
        this.triggers = Collections.unmodifiableList(new ArrayList<>(builder.triggers));
        this.references = Collections.unmodifiableList(new ArrayList<>(builder.references));
        this.examples = Collections.unmodifiableList(new ArrayList<>(builder.examples));
        this.outputFormat = builder.outputFormat;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public boolean isSystemSkill() {
        return TYPE_SYSTEM.equals(type);
    }

    public String getPath() {
        return path;
    }

    public String getSkillDir() {
        return skillDir;
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

    public List<String> getTriggers() {
        return triggers;
    }

    public List<ReferenceEntry> getReferences() {
        return references;
    }

    public List<String> getExamples() {
        return examples;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final String path;
        private String type = TYPE_TASK;
        private String skillDir;
        private String license;
        private String compatibility;
        private Map<String, String> metadata = Collections.emptyMap();
        private List<String> allowedTools = Collections.emptyList();
        private List<String> triggers = Collections.emptyList();
        private List<ReferenceEntry> references = Collections.emptyList();
        private List<String> examples = Collections.emptyList();
        private String outputFormat;

        private Builder(String name, String description, String path) {
            this.name = name;
            this.description = description;
            this.path = path;
        }

        public Builder type(String type) {
            this.type = type != null ? type : TYPE_TASK;
            return this;
        }

        public Builder skillDir(String skillDir) {
            this.skillDir = skillDir;
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

        public Builder triggers(List<String> triggers) {
            this.triggers = triggers != null ? triggers : Collections.emptyList();
            return this;
        }

        public Builder references(List<ReferenceEntry> references) {
            this.references = references != null ? references : Collections.emptyList();
            return this;
        }

        public Builder examples(List<String> examples) {
            this.examples = examples != null ? examples : Collections.emptyList();
            return this;
        }

        public Builder outputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        public SkillMetadata build() {
            return new SkillMetadata(this);
        }
    }

    public record ReferenceEntry(String file, String description) { }
}
