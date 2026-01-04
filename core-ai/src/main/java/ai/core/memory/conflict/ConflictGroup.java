package ai.core.memory.conflict;

import ai.core.memory.longterm.MemoryRecord;

import java.util.ArrayList;
import java.util.List;

/**
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

    public void addRecord(MemoryRecord record) {
        records.add(record);
    }

    public String getTopic() {
        return topic;
    }

    public List<MemoryRecord> getRecords() {
        return new ArrayList<>(records);
    }

    public int size() {
        return records.size();
    }

    public boolean hasConflict() {
        return records.size() > 1;
    }

    public double getConflictScore() {
        return conflictScore;
    }

    public void setConflictScore(double score) {
        this.conflictScore = Math.max(0.0, Math.min(1.0, score));
    }

    public MemoryRecord getNewest() {
        if (records.isEmpty()) {
            return null;
        }
        return records.stream()
            .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
            .orElse(null);
    }

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
