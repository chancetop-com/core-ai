package ai.core.server.report.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Read model returned by the report query path. It is not persisted.
 *
 * @author core-ai
 */
public class ReportData {
    public String reportId;
    public long reportRevision;
    public int specVersion;
    public String status;
    public String fromInclusive;
    public String toExclusive;
    public String timezone;
    public List<Column> columns = new ArrayList<>();
    public List<List<String>> rows = new ArrayList<>();
    public long matchedRecordCount;
    public long invalidValueCount;
    public String sourceTime;
    public String sourceUpdatedAt;
    public String evaluatedAt;
    public String freshnessStatus;
    public boolean hasMore;

    public static class Column {
        public String name;
        public String type;
        public String label;

        public Column() {
        }

        public Column(String name, String type) {
            this(name, type, null);
        }

        public Column(String name, String type, String label) {
            this.name = name;
            this.type = type;
            this.label = label;
        }
    }
}
