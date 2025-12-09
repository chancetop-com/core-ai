package ai.core.memory.model;

/**
 * Episodic memory entry for specific events, situations, and outcomes.
 *
 * @author xander
 */
public class EpisodicMemoryEntry extends MemoryEntry {
    public static Builder builder() {
        return new Builder();
    }

    private String sessionId;
    private String situation;
    private String action;
    private String outcome;

    protected EpisodicMemoryEntry() {
        this.type = MemoryType.EPISODIC;
    }

    protected EpisodicMemoryEntry(Builder builder) {
        super(builder);
        this.type = MemoryType.EPISODIC;
        this.sessionId = builder.sessionId;
        this.situation = builder.situation;
        this.action = builder.action;
        this.outcome = builder.outcome;
    }

    // Getters
    public String getSessionId() {
        return sessionId;
    }

    public String getSituation() {
        return situation;
    }

    public String getAction() {
        return action;
    }

    public String getOutcome() {
        return outcome;
    }

    /**
     * Builder for EpisodicMemoryEntry.
     */
    public static class Builder extends MemoryEntry.Builder<Builder> {
        private String sessionId;
        private String situation;
        private String action;
        private String outcome;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder situation(String situation) {
            this.situation = situation;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder outcome(String outcome) {
            this.outcome = outcome;
            return this;
        }

        @Override
        public EpisodicMemoryEntry build() {
            return new EpisodicMemoryEntry(this);
        }
    }
}
