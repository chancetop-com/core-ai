package ai.core.memory;

import java.time.Instant;

/**
 * @author xander
 */
public final class SearchFilter {

    public static Builder builder() {
        return new Builder();
    }

    private final Double minImportance;
    private final Double minDecayFactor;
    private final Instant createdAfter;
    private final Instant createdBefore;

    private SearchFilter(Builder builder) {
        this.minImportance = builder.minImportance;
        this.minDecayFactor = builder.minDecayFactor;
        this.createdAfter = builder.createdAfter;
        this.createdBefore = builder.createdBefore;
    }

    public boolean matches(MemoryRecord record) {
        if (minImportance != null && record.getImportance() < minImportance) {
            return false;
        }
        if (minDecayFactor != null && record.getDecayFactor() < minDecayFactor) {
            return false;
        }
        Instant createdAt = record.getCreatedAt();
        if (createdAfter != null && (createdAt == null || createdAt.isBefore(createdAfter))) {
            return false;
        }
        return createdBefore == null || createdAt != null && !createdAt.isAfter(createdBefore);
    }

    public static class Builder {
        private Double minImportance;
        private Double minDecayFactor;
        private Instant createdAfter;
        private Instant createdBefore;

        public Builder minImportance(double minImportance) {
            this.minImportance = minImportance;
            return this;
        }

        public Builder minDecayFactor(double minDecayFactor) {
            this.minDecayFactor = minDecayFactor;
            return this;
        }

        public SearchFilter build() {
            return new SearchFilter(this);
        }
    }
}
