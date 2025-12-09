package ai.core.memory.model;

import java.time.Instant;
import java.util.List;

/**
 * Filter criteria for memory retrieval.
 *
 * @author xander
 */
public class MemoryFilter {
    public static MemoryFilter forUser(String userId) {
        var filter = new MemoryFilter();
        filter.userId = userId;
        return filter;
    }

    public static MemoryFilter empty() {
        return new MemoryFilter();
    }

    private String userId;
    private String agentId;
    private List<MemoryType> types;
    private List<SemanticCategory> categories;
    private Double minImportance;
    private Double minStrength;
    private Double similarityThreshold;
    private Instant after;
    private Instant before;

    public MemoryFilter withUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public MemoryFilter withAgentId(String agentId) {
        this.agentId = agentId;
        return this;
    }

    public MemoryFilter withTypes(MemoryType... types) {
        this.types = List.of(types);
        return this;
    }

    public MemoryFilter withTypes(List<MemoryType> types) {
        this.types = types;
        return this;
    }

    public MemoryFilter withCategories(SemanticCategory... categories) {
        this.categories = List.of(categories);
        return this;
    }

    public MemoryFilter withMinImportance(double min) {
        this.minImportance = min;
        return this;
    }

    public MemoryFilter withMinStrength(double min) {
        this.minStrength = min;
        return this;
    }

    public MemoryFilter withSimilarityThreshold(double threshold) {
        this.similarityThreshold = threshold;
        return this;
    }

    public MemoryFilter after(Instant after) {
        this.after = after;
        return this;
    }

    public MemoryFilter before(Instant before) {
        this.before = before;
        return this;
    }

    /**
     * Check if a memory entry matches this filter.
     *
     * @param entry the memory entry to check
     * @return true if the entry matches all filter criteria
     */
    public boolean matches(MemoryEntry entry) {
        if (userId != null && !userId.equals(entry.getUserId())) {
            return false;
        }
        if (agentId != null && !agentId.equals(entry.getAgentId())) {
            return false;
        }
        if (types != null && !types.isEmpty() && !types.contains(entry.getType())) {
            return false;
        }
        if (!matchesCategory(entry)) {
            return false;
        }
        if (minImportance != null && entry.getImportance() < minImportance) {
            return false;
        }
        if (minStrength != null && entry.getStrength() < minStrength) {
            return false;
        }
        if (after != null && entry.getCreatedAt().isBefore(after)) {
            return false;
        }
        if (before != null && entry.getCreatedAt().isAfter(before)) {
            return false;
        }
        return true;
    }

    private boolean matchesCategory(MemoryEntry entry) {
        if (categories == null || categories.isEmpty()) {
            return true;
        }
        if (entry instanceof SemanticMemoryEntry sem) {
            return categories.contains(sem.getCategory());
        }
        return false;
    }

    // Getters
    public String getUserId() {
        return userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public List<MemoryType> getTypes() {
        return types;
    }

    public List<SemanticCategory> getCategories() {
        return categories;
    }

    public Double getMinImportance() {
        return minImportance;
    }

    public Double getMinStrength() {
        return minStrength;
    }

    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public Instant getAfter() {
        return after;
    }

    public Instant getBefore() {
        return before;
    }
}
