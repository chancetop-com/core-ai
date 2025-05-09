package ai.core.task;

/**
 * @author stephen
 */
public abstract class Part<T extends Part<T>> {
    private final PartType type;

    public Part(PartType type) {
        this.type = type;
    }

    public PartType getType() {
        return type;
    }
}
