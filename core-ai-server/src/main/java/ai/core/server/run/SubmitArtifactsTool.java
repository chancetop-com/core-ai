package ai.core.server.run;

import ai.core.agent.ExecutionContext;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.file.FileService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;
import core.framework.mongo.MongoCollection;
import core.framework.util.Strings;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author xander
 */
public class SubmitArtifactsTool extends ToolCall {
    public static final String TOOL_NAME = "submit_artifacts";
    private static final String TOOL_DESC = """
            Submit files created in the sandbox as downloadable run artifacts.

            Use this tool when you create files that the caller should download or reuse, such as reports,
            charts, PDFs, CSVs, images, spreadsheets, or archives. The files must already exist inside the
            sandbox, usually under /tmp or /workspace. Do not include file contents in this tool call; provide
            only the sandbox path and optional metadata.
            """;

    private final String runId;
    private final String userId;
    private final FileService fileService;
    private final MongoCollection<AgentRun> agentRunCollection;

    public static SubmitArtifactsTool create(String runId, String userId, FileService fileService, MongoCollection<AgentRun> agentRunCollection) {
        var tool = new SubmitArtifactsTool(runId, userId, fileService, agentRunCollection);
        tool.setName(TOOL_NAME);
        tool.setDescription(TOOL_DESC);
        tool.setParameters(parameters());
        tool.setNeedAuth(false);
        tool.setDirectReturn(false);
        tool.setLlmVisible(true);
        tool.setDiscoverable(false);
        return tool;
    }

    private SubmitArtifactsTool(String runId, String userId, FileService fileService, MongoCollection<AgentRun> agentRunCollection) {
        this.runId = runId;
        this.userId = userId;
        this.fileService = fileService;
        this.agentRunCollection = agentRunCollection;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("submit_artifacts requires ExecutionContext; direct execute is not supported");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var sandbox = context != null ? context.getSandbox() : null;
        if (sandbox == null) {
            return ToolCallResult.failed("submit_artifacts requires an active sandbox");
        }

        Request request;
        try {
            request = JsonUtil.fromJson(Request.class, arguments);
        } catch (Exception e) {
            return ToolCallResult.failed("failed to parse submit_artifacts arguments: " + e.getMessage(), e);
        }

        if (request.artifacts == null || request.artifacts.isEmpty()) {
            return ToolCallResult.failed("artifacts is required and must not be empty");
        }

        var submitted = new ArrayList<Map<String, Object>>();
        var failed = new ArrayList<Map<String, Object>>();
        for (var item : request.artifacts) {
            if (item == null || Strings.isBlank(item.path)) {
                failed.add(Map.of("path", "", "error", "path is required"));
                continue;
            }
            try {
                var sandboxFile = sandbox.downloadFile(item.path);
                var fileName = !Strings.isBlank(item.name) ? item.name : sandboxFile.fileName();
                var contentType = !Strings.isBlank(item.contentType) ? item.contentType : sandboxFile.contentType();
                var record = fileService.upload(userId, fileName, contentType, sandboxFile.path());

                var artifact = new AgentRunArtifact();
                artifact.fileId = record.id;
                artifact.fileName = record.fileName;
                artifact.contentType = record.contentType;
                artifact.size = record.size;
                artifact.sourcePath = item.path;
                artifact.title = item.title;
                artifact.description = item.description;
                artifact.createdAt = ZonedDateTime.now();
                appendArtifact(artifact);

                submitted.add(Map.of(
                    "path", item.path,
                    "file_id", record.id,
                    "file_name", record.fileName,
                    "download_url", "/api/files/" + record.id + "/content"
                ));
            } catch (Exception e) {
                failed.add(Map.of("path", item.path, "error", errorMessage(e)));
            }
        }

        var result = Map.of(
            "submitted", submitted,
            "failed", failed
        );
        if (submitted.isEmpty()) {
            return ToolCallResult.failed(JsonUtil.toJson(result));
        }
        return ToolCallResult.completed(JsonUtil.toJson(result));
    }

    private String errorMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void appendArtifact(AgentRunArtifact artifact) {
        var run = agentRunCollection.get(runId)
            .orElseThrow(() -> new RuntimeException("run not found, id=" + runId));
        var artifacts = run.artifacts != null ? new ArrayList<>(run.artifacts) : new ArrayList<AgentRunArtifact>();
        artifacts.add(artifact);
        run.artifacts = artifacts;
        agentRunCollection.replace(run);
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(List.class, "artifacts", """
                Array of artifact objects. Each item must include path, the sandbox file path to submit.
                Optional item fields: name, title, description, content_type.
                Example: [{"path":"/tmp/report.pdf","name":"report.pdf","title":"Analysis report","content_type":"application/pdf"}]
                """).required()
        );
    }

    public static class Request {
        public List<ArtifactRequest> artifacts;
    }

    public static class ArtifactRequest {
        public String path;
        public String name;
        public String title;
        public String description;
        @Property(name = "content_type")
        public String contentType;
    }
}
