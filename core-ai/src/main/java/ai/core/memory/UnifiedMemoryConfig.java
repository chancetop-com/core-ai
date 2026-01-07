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

    public boolean isAutoRecall() {
        return autoRecall;
    }

    public int getMaxRecallRecords() {
        return maxRecallRecords;
    }

    public static final class Builder {
        private final UnifiedMemoryConfig config;

        private Builder() {
            this.config = new UnifiedMemoryConfig();
        }

        public Builder autoRecall(boolean autoRecall) {
            config.autoRecall = autoRecall;
            return this;
        }

        public Builder maxRecallRecords(int maxRecords) {
            config.maxRecallRecords = Math.max(1, Math.min(20, maxRecords));
            return this;
        }

        public UnifiedMemoryConfig build() {
            return config;
        }
    }
}
