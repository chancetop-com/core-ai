package ai.core.server.report.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * One rendered report: a single component for legacy reports, several components for a parameterized
 * dashboard. Components share the report id/revision and are evaluated with the same parameter bindings.
 *
 * @author core-ai
 */
public class ReportDataBundle {
    public String reportId;
    public long reportRevision;
    public int specVersion;
    public List<Component> components = new ArrayList<>();

    public static class Component {
        public String id;
        public String title;
        public String displayType;
        public ReportData data;
    }
}
