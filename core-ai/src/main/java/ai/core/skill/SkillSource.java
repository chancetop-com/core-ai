package ai.core.skill;

import java.util.Objects;

/**
 * @author xander
 */
public final class SkillSource implements Comparable<SkillSource> {
    private final String name;
    private final String path;
    private final int priority;

    public SkillSource(String name, String path, int priority) {
        this.name = name;
        this.path = path;
        this.priority = priority;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public int compareTo(SkillSource other) {
        return Integer.compare(this.priority, other.priority);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SkillSource that = (SkillSource) o;
        return priority == that.priority && Objects.equals(name, that.name) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path, priority);
    }
}
