package ai.core.agent.slidingwindow;

/**
 * @author stephen
 */
public final class SlidingWindowConfig {
    public static Builder builder() {
        return new Builder();
    }

    private final Integer maxTurns;
    private final Double triggerThreshold;
    private final Double targetThreshold;
    private final boolean autoTokenProtection;

    private SlidingWindowConfig(Builder builder) {
        this.maxTurns = builder.maxTurns;
        this.triggerThreshold = builder.triggerThreshold;
        this.targetThreshold = builder.targetThreshold;
        this.autoTokenProtection = builder.autoTokenProtection;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }

    public Double getTriggerThreshold() {
        return triggerThreshold;
    }

    public Double getTargetThreshold() {
        return targetThreshold;
    }

    public boolean isAutoTokenProtection() {
        return autoTokenProtection;
    }

    public static class Builder {
        private Integer maxTurns;
        private Double triggerThreshold = 0.8;
        private Double targetThreshold = 0.6;
        private boolean autoTokenProtection = true;

        public Builder maxTurns(Integer maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder triggerThreshold(Double triggerThreshold) {
            this.triggerThreshold = triggerThreshold;
            return this;
        }

        public Builder targetThreshold(Double targetThreshold) {
            this.targetThreshold = targetThreshold;
            return this;
        }

        public Builder autoTokenProtection(boolean autoTokenProtection) {
            this.autoTokenProtection = autoTokenProtection;
            return this;
        }

        public SlidingWindowConfig build() {
            return new SlidingWindowConfig(this);
        }
    }
}
