package ai.core.memory.conflict;

import ai.core.memory.longterm.MemoryRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a group of conflicting memory records.
 *
 * <p>Memory records are considered conflicting when they:
 * - Have high semantic similarity but different content
 * - Cover the same topic or fact with contradicting information
 *
 * @author xander
 */
public class ConflictGroup {

    private final String topic;
    private final List<MemoryRecord> records;
    private double conflictScore;

    public ConflictGroup(String topic) {
        this.topic = topic;
        this.records = new ArrayList<>();
        this.conflictScore = 0.0;
    }

    public ConflictGroup(String topic, List<MemoryRecord> records) {
        this.topic = topic;
        this.records = new ArrayList<>(records);
        this.conflictScore = 0.0;
    }

    /**
     * Add a record to this conflict group.
     *
     * @param record the memory record to add
     */
    public void addRecord(MemoryRecord record) {
        records.add(record);
    }

    /**
     * Get the topic/category of this conflict.
     *
     * @return the topic string
     */
    public String getTopic() {
        return topic;
    }

    /**
     * Get all records in this conflict group.
     *
     * @return list of memory records
     */
    public List<MemoryRecord> getRecords() {
        return new ArrayList<>(records);
    }

    /**
     * Get the number of conflicting records.
     *
     * @return size of the group
     */
    public int size() {
        return records.size();
    }

    /**
     * Check if this group has conflicts.
     *
     * @return true if more than one record exists
     */
    public boolean hasConflict() {
        return records.size() > 1;
    }

    /**
     * Get the conflict score (0-1).
     * Higher score indicates more severe conflict.
     *
     * @return conflict score
     */
    public double getConflictScore() {
        return conflictScore;
    }

    /**
     * Set the conflict score.
     *
     * @param score the conflict score (0-1)
     */
    public void setConflictScore(double score) {
        this.conflictScore = Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Get the newest record in this group.
     *
     * @return the most recently created record, or null if empty
     */
    public MemoryRecord getNewest() {
        if (records.isEmpty()) {
            return null;
        }
        return records.stream()
            .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
            .orElse(null);
    }

    /**
     * Get the most important record in this group.
     *
     * @return the record with highest importance, or null if empty
     */
    public MemoryRecord getMostImportant() {
        if (records.isEmpty()) {
            return null;
        }
        return records.stream()
            .max((a, b) -> Double.compare(a.getImportance(), b.getImportance()))
            .orElse(null);
    }

    @Override
    public String toString() {
        return "ConflictGroup{"
            + "topic='" + topic + '\''
            + ", recordCount=" + records.size()
            + ", conflictScore=" + conflictScore
            + '}';
    }
}
