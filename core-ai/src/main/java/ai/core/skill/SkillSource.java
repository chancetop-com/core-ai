package ai.core.skill;

/**
 * @author xander
 */
public record SkillSource(String name, String path, int priority) implements Comparable<SkillSource> {

    @Override
    public int compareTo(SkillSource other) {
        return Integer.compare(this.priority, other.priority);
    }

}
