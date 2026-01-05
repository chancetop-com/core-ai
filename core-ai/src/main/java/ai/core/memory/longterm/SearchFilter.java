package ai.core.memory.longterm;

import java.time.Instant;

/**
 * Filter for memory search operations.
 *
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

    public Double getMinImportance() {
        return minImportance;
    }

    public Double getMinDecayFactor() {
        return minDecayFactor;
    }

    public Instant getCreatedAfter() {
        return createdAfter;
    }

    public Instant getCreatedBefore() {
        return createdBefore;
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
        if (createdBefore != null && (createdAt == null || createdAt.isAfter(createdBefore))) {
            return false;
        }
        return true;
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

        public Builder createdAfter(Instant after) {
            this.createdAfter = after;
            return this;
        }

        public Builder createdBefore(Instant before) {
            this.createdBefore = before;
            return this;
        }

        public SearchFilter build() {
            return new SearchFilter(this);
        }
    }
}
