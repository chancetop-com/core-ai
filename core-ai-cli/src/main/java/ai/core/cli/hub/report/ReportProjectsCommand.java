package ai.core.cli.hub.report;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the projects (and their subjects) a report can be pushed into, so users can find the names/ids
 * for {@code report push --project ... --subject ...}.
 *
 * @author stephen
 */
@Command(name = "projects", description = "List projects and their subjects available for report push")
class ReportProjectsCommand extends HubCommandBase {
    @Option(names = "--no-subjects", description = "Only list projects, skip the per-project subject lookup")
    boolean noSubjects;

    @Override
    protected Integer execute() {
        var client = reportClient();
        var rows = new ArrayList<Map<String, Object>>();
        for (var project : client.projects()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", project.id);
            row.put("name", project.name);
            row.put("description", project.description);
            if (!noSubjects) {
                var subjects = new ArrayList<Map<String, Object>>();
                for (var subject : client.subjects(project.id)) {
                    var s = new LinkedHashMap<String, Object>();
                    s.put("id", subject.id);
                    s.put("name", subject.name);
                    s.put("status", subject.status);
                    subjects.add(s);
                }
                row.put("subjects", subjects);
            }
            rows.add(row);
        }
        if (json()) {
            HubRenderer.printJson(rows);
            return HubExitCodes.SUCCESS;
        }
        if (rows.isEmpty()) {
            ConsoleWriter.println("  (no projects visible to this account)");
            return HubExitCodes.SUCCESS;
        }
        for (var row : rows) {
            ConsoleWriter.println(row.get("name") + "  [" + row.get("id") + "]");
            @SuppressWarnings("unchecked")
            var subjects = (List<Map<String, Object>>) row.get("subjects");
            if (subjects == null) continue;
            if (subjects.isEmpty()) ConsoleWriter.println("    (no subjects)");
            for (var s : subjects) {
                ConsoleWriter.println("    - " + s.get("name") + "  [" + s.get("id") + "]" + (s.get("status") != null ? "  " + s.get("status") : ""));
            }
        }
        return HubExitCodes.SUCCESS;
    }
}
