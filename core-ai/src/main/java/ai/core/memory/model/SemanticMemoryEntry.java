package ai.core.memory.model;

import java.util.Locale;

/**
 * Semantic memory entry for facts, preferences, and knowledge.
 * Supports SPO (Subject-Predicate-Object) structure for KV storage lookup.
 *
 * @author xander
 */
public class SemanticMemoryEntry extends MemoryEntry {
    public static Builder builder() {
        return new Builder();
    }

    private SemanticCategory category;
    private String subject;
    private String predicate;
    private String object;

    protected SemanticMemoryEntry() {
        this.type = MemoryType.SEMANTIC;
    }

    protected SemanticMemoryEntry(Builder builder) {
        super(builder);
        this.type = MemoryType.SEMANTIC;
        this.category = builder.category;
        this.subject = builder.subject;
        this.predicate = builder.predicate;
        this.object = builder.object;
    }

    // Getters
    public SemanticCategory getCategory() {
        return category;
    }

    public String getSubject() {
        return subject;
    }

    public String getPredicate() {
        return predicate;
    }

    public String getObject() {
        return object;
    }

    /**
     * Build a KV store key from subject.
     *
     * @return key in format "userId:subject" or just "subject" if no userId
     */
    public String buildKvKey() {
        if (getUserId() != null && subject != null) {
            return getUserId() + ":" + subject.toLowerCase(Locale.ROOT);
        }
        return subject != null ? subject.toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Builder for SemanticMemoryEntry.
     */
    public static class Builder extends MemoryEntry.Builder<Builder> {
        private SemanticCategory category;
        private String subject;
        private String predicate;
        private String object;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder category(SemanticCategory category) {
            this.category = category;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder predicate(String predicate) {
            this.predicate = predicate;
            return this;
        }

        public Builder object(String object) {
            this.object = object;
            return this;
        }

        @Override
        public SemanticMemoryEntry build() {
            return new SemanticMemoryEntry(this);
        }
    }
}
