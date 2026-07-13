package ai.core.server.memory.experiment;

import java.util.List;

/**
 * Default values for memory injection policy.
 *
 * @author stephen
 */
public final class MemoryPolicy {

    public static final RankingStrategy DEFAULT_RANKING = RankingStrategy.SEMANTIC;
    public static final List<MemoryLayer> DEFAULT_LAYERS = List.of(MemoryLayer.KNOWLEDGE, MemoryLayer.METHODS, MemoryLayer.TRAJECTORIES);
    public static final int DEFAULT_TOP_K = 5;
    public static final double DEFAULT_INJECTION_PROBABILITY = 1.0;

    private MemoryPolicy() {
    }
}
