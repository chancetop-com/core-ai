package ai.core.cli.hub.report;

import ai.core.api.server.project.ListProjectSubjectsResponse;
import ai.core.api.server.project.ListProjectsResponse;
import ai.core.api.server.project.ProjectReportView;
import ai.core.api.server.project.ProjectSubjectView;
import ai.core.api.server.project.ProjectSummaryView;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin client of the project endpoints the {@code report} commands need: list projects, list a project's
 * subjects and push a report file into a subject ({@code POST /api/projects/:id/subjects/:subjectId/reports},
 * requires {@code project.manage} on the server).
 *
 * @author stephen
 */
public class ReportHubClient {
    private static final String CLIENT_HEADER = "x-core-ai-client";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(5);

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private final String serverUrl;
    private final String apiKey;
    private final boolean insecure;

    public ReportHubClient(String serverUrl, String apiKey, boolean insecure) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.insecure = insecure;
    }

    public String serverUrl() {
        return serverUrl;
    }

    public List<ProjectSummaryView> projects() {
        var body = apiClient(TIMEOUT).getRequired("/api/projects?offset=0&limit=200&archived=false");
        var response = JsonUtil.fromJson(ListProjectsResponse.class, body);
        return response.projects != null ? response.projects : List.of();
    }

    public List<ProjectSubjectView> subjects(String projectId) {
        var body = apiClient(TIMEOUT).getRequired("/api/projects/" + encode(projectId) + "/subjects?offset=0&limit=200");
        var response = JsonUtil.fromJson(ListProjectSubjectsResponse.class, body);
        return response.subjects != null ? response.subjects : List.of();
    }

    public ProjectReportView push(String projectId, String subjectId, Path file, String fileName) {
        var path = "/api/projects/" + encode(projectId) + "/subjects/" + encode(subjectId) + "/reports";
        if (fileName != null && !fileName.isBlank()) path += "?file_name=" + encode(fileName);
        var body = apiClient(UPLOAD_TIMEOUT).postMultipart(path, Map.of("file", file));
        if (body == null) return null;
        return JsonUtil.fromJson(ProjectReportView.class, body);
    }

    private RemoteApiClient apiClient(Duration timeout) {
        return new RemoteApiClient(serverUrl, apiKey, timeout, Map.of(CLIENT_HEADER, "cli"), insecure);
    }
}
