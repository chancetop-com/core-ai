package ai.core.cli.hub.report;

import ai.core.api.server.project.ProjectSubjectView;
import ai.core.api.server.project.ProjectSummaryView;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Publishes a locally generated report into a core-ai-server project subject, so colleagues browse it under
 * the right business line instead of receiving the raw HTML file. Project and subject accept either an id or
 * an exact (case-insensitive) name.
 *
 * @author stephen
 */
@Command(name = "push", description = "Upload a local report file into a project subject on core-ai-server")
class ReportPushCommand extends HubCommandBase {
    static ProjectSummaryView resolveProject(ReportHubClient client, String idOrName) {
        var projects = client.projects();
        var byId = projects.stream().filter(p -> idOrName.equals(p.id)).findFirst();
        if (byId.isPresent()) return byId.get();
        var byName = projects.stream().filter(p -> p.name != null && p.name.trim().equalsIgnoreCase(idOrName.trim())).toList();
        if (byName.size() == 1) return byName.getFirst();
        if (byName.size() > 1) throw new HubCliError(HubExitCodes.USAGE, "project name is ambiguous, pass the id instead: " + idOrName);
        throw new HubCliError(HubExitCodes.NOT_FOUND, "project not found: " + idOrName + hint("projects", projects.stream().map(p -> p.name).toList()));
    }

    static ProjectSubjectView resolveSubject(ReportHubClient client, ProjectSummaryView project, String idOrName) {
        var subjects = client.subjects(project.id);
        var byId = subjects.stream().filter(s -> idOrName.equals(s.id)).findFirst();
        if (byId.isPresent()) return byId.get();
        var byName = subjects.stream().filter(s -> s.name != null && s.name.trim().equalsIgnoreCase(idOrName.trim())).toList();
        if (byName.size() == 1) return byName.getFirst();
        if (byName.size() > 1) throw new HubCliError(HubExitCodes.USAGE, "subject name is ambiguous, pass the id instead: " + idOrName);
        throw new HubCliError(HubExitCodes.NOT_FOUND, "subject not found in project " + project.name + ": " + idOrName
            + hint("subjects", subjects.stream().map(s -> s.name).toList()));
    }

    private static String hint(String label, List<String> names) {
        var known = names.stream().filter(n -> n != null && !n.isBlank()).toList();
        if (known.isEmpty()) return "";
        return " (available " + label + ": " + String.join(", ", known) + ")";
    }

    @Parameters(index = "0", paramLabel = "file", description = "Report file (html, pdf, md, csv, ...)")
    Path file;

    @Option(names = {"-p", "--project"}, required = true, description = "Project id or name")
    String project;

    @Option(names = {"-s", "--subject"}, required = true, description = "Subject id or name inside the project (the business line)")
    String subject;

    @Option(names = "--name", description = "Display name on the server (defaults to the file name)")
    String name;

    @Override
    protected Integer execute() {
        var absolute = file.toAbsolutePath();
        if (!Files.isRegularFile(absolute)) {
            throw new HubCliError(HubExitCodes.USAGE, "file not found: " + absolute);
        }
        var client = reportClient();
        var resolvedProject = resolveProject(client, project);
        var resolvedSubject = resolveSubject(client, resolvedProject, subject);
        metadata("uploading " + absolute.getFileName() + " into " + resolvedProject.name + " / " + resolvedSubject.name + " ...");
        var view = client.push(resolvedProject.id, resolvedSubject.id, absolute, name);
        if (view == null) {
            throw new HubCliError(HubExitCodes.TOOL_ERROR, "upload failed");
        }
        var shareUrl = view.shareToken != null ? client.serverUrl() + "/shared/artifacts/" + view.shareToken : null;
        var subjectUrl = client.serverUrl() + "/projects/" + resolvedProject.id + "/subjects/" + resolvedSubject.id;
        if (json()) {
            var out = new LinkedHashMap<String, Object>();
            out.put("file_id", view.fileId);
            out.put("file_name", view.fileName);
            out.put("project_id", resolvedProject.id);
            out.put("project_name", resolvedProject.name);
            out.put("subject_id", resolvedSubject.id);
            out.put("subject_name", resolvedSubject.name);
            out.put("share_url", shareUrl);
            out.put("subject_url", subjectUrl);
            HubRenderer.printJson(out);
        } else {
            ConsoleWriter.println("Uploaded " + view.fileName + " -> " + resolvedProject.name + " / " + resolvedSubject.name);
            if (shareUrl != null) ConsoleWriter.println("  share:   " + shareUrl);
            ConsoleWriter.println("  subject: " + subjectUrl);
        }
        return HubExitCodes.SUCCESS;
    }
}
