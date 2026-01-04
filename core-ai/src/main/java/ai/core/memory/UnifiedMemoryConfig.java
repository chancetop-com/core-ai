package ai.core.memory;

/**
 * Configuration for unified memory lifecycle.
 *
 * @author xander
 */
public final class UnifiedMemoryConfig {

    private static final int DEFAULT_MAX_RECALL_RECORDS = 5;

    public static Builder builder() {
        return new Builder();
    }

    public static UnifiedMemoryConfig defaultConfig() {
        return builder().build();
    }

    private boolean autoRecall;
    private int maxRecallRecords;

    private UnifiedMemoryConfig() {
        this.autoRecall = true;
        this.maxRecallRecords = DEFAULT_MAX_RECALL_RECORDS;
    }

    /**
     * Check if MemoryRecallTool should be auto-registered to agent.
     */
    public boolean isAutoRecall() {
        return autoRecall;
    }

    /**
     * Get maximum number of records to recall.
     */
    public int getMaxRecallRecords() {
        return maxRecallRecords;
    }

    public static final class Builder {
        private final UnifiedMemoryConfig config;

        private Builder() {
            this.config = new UnifiedMemoryConfig();
        }

        /**
         * Enable or disable auto-registration of MemoryRecallTool.
         */
        public Builder autoRecall(boolean autoRecall) {
            config.autoRecall = autoRecall;
            return this;
        }

        /**
         * Set maximum number of records to recall.
         */
        public Builder maxRecallRecords(int maxRecords) {
            config.maxRecallRecords = Math.max(1, Math.min(20, maxRecords));
            return this;
        }

        public UnifiedMemoryConfig build() {
            return config;
        }
    }
}
