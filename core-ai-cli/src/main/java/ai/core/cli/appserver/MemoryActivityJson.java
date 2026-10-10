package ai.core.cli.appserver;

import ai.core.cli.memory.MemoryExtractionReport;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * Builds the {@code memory} custom event payload the desktop renders. The fields are written
 * explicitly because the report's record components are not visible to Jackson under core-ng's
 * property rules (a record serializes to {@code {}}).
 *
 * @author stephen
 */
final class MemoryActivityJson {
    /**
     * @return {@code {runId, phase, trigger}} while a run is in progress, plus
     *         {@code {durationMs, cursor, added[], updated[], note?}} once it completed
     */
    static ObjectNode payload(MemoryExtractionReport report) {
        var node = Params.object();
        node.put("runId", report.runId());
        node.put("phase", report.phase().name().toLowerCase(Locale.ROOT));
        node.put("trigger", report.trigger().name().toLowerCase(Locale.ROOT));
        if (report.phase() == MemoryExtractionReport.Phase.COMPLETED) {
            node.put("durationMs", report.durationMs());
            node.put("cursor", report.cursor());
            var added = node.putArray("added");
            report.added().forEach(added::add);
            var updated = node.putArray("updated");
            report.updated().forEach(updated::add);
            if (report.note() != null) {
                node.put("note", report.note());
            }
        }
        return node;
    }

    private MemoryActivityJson() {
    }
}
