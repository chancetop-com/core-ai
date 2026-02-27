package ai.core.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author xander
 */
public final class SkillConfig {
    private static final int DEFAULT_MAX_SKILL_FILE_SIZE = 10 * 1024 * 1024;

    public static Builder builder() {
        return new Builder();
    }

    public static SkillConfig of(String... paths) {
        var builder = new Builder();
        for (int i = 0; i < paths.length; i++) {
            builder.source("source-" + i, paths[i], i);
        }
        return builder.build();
    }

    public static SkillConfig disabled() {
        return new SkillConfig(Collections.emptyList(), false, DEFAULT_MAX_SKILL_FILE_SIZE);
    }

    private final List<SkillSource> sources;
    private final boolean enabled;
    private final int maxSkillFileSize;

    private SkillConfig(List<SkillSource> sources, boolean enabled, int maxSkillFileSize) {
        this.sources = sources;
        this.enabled = enabled;
        this.maxSkillFileSize = maxSkillFileSize;
    }

    public List<SkillSource> getSources() {
        return Collections.unmodifiableList(sources);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxSkillFileSize() {
        return maxSkillFileSize;
    }

    public static final class Builder {
        private final List<SkillSource> sources = new ArrayList<>();
        private boolean enabled = true;
        private int maxSkillFileSize = DEFAULT_MAX_SKILL_FILE_SIZE;

        private Builder() {
        }

        public Builder source(String name, String path, int priority) {
            sources.add(new SkillSource(name, path, priority));
            return this;
        }

        public Builder workspace() {
            String cwd = System.getProperty("user.dir");
            sources.add(new SkillSource("workspace", cwd + "/.core-ai/skills", 100));
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder maxSkillFileSize(int maxSkillFileSize) {
            if (maxSkillFileSize <= 0) throw new IllegalArgumentException("maxSkillFileSize must be positive");
            this.maxSkillFileSize = maxSkillFileSize;
            return this;
        }

        public SkillConfig build() {
            Collections.sort(sources);
            return new SkillConfig(new ArrayList<>(sources), enabled, maxSkillFileSize);
        }
    }
}
