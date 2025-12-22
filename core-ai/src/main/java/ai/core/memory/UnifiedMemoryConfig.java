package ai.core.memory;

import ai.core.memory.conflict.ConflictStrategy;

/**
 * Configuration for unified memory system.
 *
 * <p>Controls automatic memory recall, transition, and conflict handling.
 *
 * @author xander
 */
public final class UnifiedMemoryConfig {

    private static final int DEFAULT_MAX_RECALL_RECORDS = 5;
    private static final double DEFAULT_MEMORY_BUDGET_RATIO = 0.2;

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create default configuration with all features enabled.
     *
     * @return default config
     */
    public static UnifiedMemoryConfig defaultConfig() {
        return builder().build();
    }

    /**
     * Create config with only recall enabled (no auto-transition).
     *
     * @return recall-only config
     */
    public static UnifiedMemoryConfig recallOnly() {
        return builder()
            .autoRecall(true)
            .autoTransition(false)
            .build();
    }

    private boolean autoRecall;
    private boolean autoTransition;
    private int maxRecallRecords;
    private double memoryBudgetRatio;
    private ConflictStrategy conflictStrategy;

    private UnifiedMemoryConfig() {
        this.autoRecall = true;
        this.autoTransition = true;
        this.maxRecallRecords = DEFAULT_MAX_RECALL_RECORDS;
        this.memoryBudgetRatio = DEFAULT_MEMORY_BUDGET_RATIO;
        this.conflictStrategy = ConflictStrategy.NEWEST_WITH_MERGE;
    }

    /**
     * Check if automatic memory recall is enabled.
     *
     * @return true if auto-recall is enabled
     */
    public boolean isAutoRecall() {
        return autoRecall;
    }

    /**
     * Check if automatic STM to LTM transition is enabled.
     *
     * @return true if auto-transition is enabled
     */
    public boolean isAutoTransition() {
        return autoTransition;
    }

    /**
     * Get maximum number of records to recall.
     *
     * @return max recall records
     */
    public int getMaxRecallRecords() {
        return maxRecallRecords;
    }

    /**
     * Get the memory budget ratio (0-1).
     *
     * @return memory budget ratio
     */
    public double getMemoryBudgetRatio() {
        return memoryBudgetRatio;
    }

    /**
     * Get the conflict resolution strategy.
     *
     * @return conflict strategy
     */
    public ConflictStrategy getConflictStrategy() {
        return conflictStrategy;
    }

    /**
     * Builder for UnifiedMemoryConfig.
     */
    public static final class Builder {
        private final UnifiedMemoryConfig config;

        private Builder() {
            this.config = new UnifiedMemoryConfig();
        }

        /**
         * Enable or disable automatic memory recall before model calls.
         *
         * @param autoRecall true to enable
         * @return this builder
         */
        public Builder autoRecall(boolean autoRecall) {
            config.autoRecall = autoRecall;
            return this;
        }

        /**
         * Enable or disable automatic STM to LTM transition on session end.
         *
         * @param autoTransition true to enable
         * @return this builder
         */
        public Builder autoTransition(boolean autoTransition) {
            config.autoTransition = autoTransition;
            return this;
        }

        /**
         * Set maximum number of records to recall.
         *
         * @param maxRecords max records (1-20)
         * @return this builder
         */
        public Builder maxRecallRecords(int maxRecords) {
            config.maxRecallRecords = Math.max(1, Math.min(20, maxRecords));
            return this;
        }

        /**
         * Set the memory budget ratio (portion of context window for memory).
         *
         * @param ratio budget ratio (0.05-0.5)
         * @return this builder
         */
        public Builder memoryBudgetRatio(double ratio) {
            config.memoryBudgetRatio = Math.max(0.05, Math.min(0.5, ratio));
            return this;
        }

        /**
         * Set the conflict resolution strategy.
         *
         * @param strategy conflict strategy
         * @return this builder
         */
        public Builder conflictStrategy(ConflictStrategy strategy) {
            config.conflictStrategy = strategy != null ? strategy : ConflictStrategy.NEWEST_WITH_MERGE;
            return this;
        }

        /**
         * Build the configuration.
         *
         * @return built config
         */
        public UnifiedMemoryConfig build() {
            return config;
        }
    }
}
