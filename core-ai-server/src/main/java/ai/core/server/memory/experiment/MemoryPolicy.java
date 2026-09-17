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
    public static final InjectionMode DEFAULT_INJECTION_MODE = InjectionMode.LAYERED;

    /** Knowledge rows always written out in full (newest first) before the index is built. */
    public static final int KNOWLEDGE_FULL_MAX = 20;

    /** Methods / trajectories listed as ids; each line costs its id plus a one-line summary. */
    public static final int INDEX_MAX = 20;

    public static final int INDEX_SUMMARY_MAX_CHARS = 120;

    private MemoryPolicy() {
    }
}
