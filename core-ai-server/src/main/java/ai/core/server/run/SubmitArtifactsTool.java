package ai.core.server.run;

import ai.core.agent.ExecutionContext;
import ai.core.server.artifact.ArtifactSink;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.file.FileService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import core.framework.api.json.Property;
import core.framework.util.Strings;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author xander
 */
public final class SubmitArtifactsTool extends ToolCall {
    public static final String TOOL_NAME = "submit_artifacts";
    public static final String SYSTEM_INSTRUCTIONS = """

        # Platform artifact delivery

        When you create or update files in the sandbox that are intended for the caller to download or reuse
        (for example PDFs, reports, charts, CSVs, spreadsheets, images, or archives), you must call the
        `submit_artifacts` tool before your final response. Submit the sandbox file paths, usually under
        `/tmp` or `/workspace`, with concise names and content types when known.

        The tool returns each submitted artifact's `file_id` and a `download_url` (a fully-qualified
        absolute URL, typically under `/api/public/artifacts/.../content`). If you reference
        any submitted file in your final markdown reply (especially images), you MUST copy that exact
        `download_url` from the tool result — never a sandbox relative path such as `chart.png` or
        `/workspace/chart.png`, because the browser cannot resolve those and the image will appear broken.
        Correct:   `![chart](<download_url from tool result>)`
        Incorrect: `![chart](chart.png)` or `![chart](/workspace/chart.png)`

        This is a platform delivery requirement. It does not change the user's requested final response format:
        after submitting artifacts, still answer exactly as the task instructions require.
        """;

    private static final String TOOL_DESC = """
            Submit files created in the sandbox as downloadable run artifacts.

            Use this tool when you create files that the caller should download or reuse, such as reports,
            charts, PDFs, CSVs, images, spreadsheets, or archives. The files must already exist inside the
            sandbox, usually under /tmp or /workspace. Do not include file contents in this tool call; provide
            only the sandbox path and optional metadata.
            """;

    public static String appendInstructions(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) return SYSTEM_INSTRUCTIONS.strip();
        return systemPrompt + SYSTEM_INSTRUCTIONS;
    }

    public static SubmitArtifactsTool create(String userId, FileService fileService, ArtifactSink sink,
                                             PublicUrlConfiguration publicUrlConfiguration) {
        var tool = new SubmitArtifactsTool(userId, fileService, sink, publicUrlConfiguration);
        tool.setName(TOOL_NAME);
        tool.setDescription(TOOL_DESC);
        tool.setParameters(parameters());
        tool.setNeedAuth(Boolean.FALSE);
        tool.setDirectReturn(Boolean.FALSE);
        tool.setLlmVisible(Boolean.TRUE);
        tool.setDiscoverable(Boolean.FALSE);
        return tool;
    }

    private static List<ToolCallParameter> parameters() {
        return ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(List.class, "artifacts", """
                Array of artifact objects. Each item MUST be a JSON object (not a bare string) with a required
                `path` field — the sandbox file path to submit. Optional object fields: name, title, description, content_type.
                Correct example: [{"path":"/tmp/report.pdf","name":"report.pdf","title":"Analysis","content_type":"application/pdf"}]
                Do NOT pass an array of strings like ["/tmp/report.pdf"].
                """).required()
        );
    }

    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    static String normalizeArguments(String arguments) {
        if (Strings.isBlank(arguments)) return arguments;
        try {
            var root = JsonUtil.OBJECT_MAPPER.readTree(arguments);
            var artifactsNode = root.get("artifacts");
            if (artifactsNode == null) return arguments;
            // Some LLMs double-encode the artifacts array as a JSON string,
            // e.g. {"artifacts":"[{\"path\":\"/tmp/x\"}]"} instead of {"artifacts":[{"path":"/tmp/x"}]}.
            // Unwrap the inner JSON before normal array handling.
            if (artifactsNode.isTextual()) {
                var parsed = JsonUtil.OBJECT_MAPPER.readTree(artifactsNode.asText());
                if (!parsed.isArray()) return arguments;
                artifactsNode = parsed;
            }
            if (!artifactsNode.isArray()) return arguments;
            var rewritten = JsonUtil.OBJECT_MAPPER.createArrayNode();
            for (var item : artifactsNode) {
                if (item.isTextual()) {
                    rewritten.add(JsonUtil.OBJECT_MAPPER.createObjectNode().put("path", item.asText()));
                } else {
                    rewritten.add(item);
                }
            }
            ((ObjectNode) root).set("artifacts", rewritten);
            return JsonUtil.OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return arguments;
        }
    }

    private final String userId;
    private final FileService fileService;
    private final ArtifactSink sink;
    private final PublicUrlConfiguration publicUrlConfiguration;

    private SubmitArtifactsTool(String userId, FileService fileService, ArtifactSink sink,
                                PublicUrlConfiguration publicUrlConfiguration) {
        this.userId = userId;
        this.fileService = fileService;
        this.sink = sink;
        this.publicUrlConfiguration = publicUrlConfiguration;
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
            request = JsonUtil.fromJson(Request.class, normalizeArguments(arguments));
        } catch (Exception e) {
            return ToolCallResult.failed("failed to parse submit_artifacts arguments: " + e.getMessage(), e);
        }

        if (request.artifacts == null || request.artifacts.isEmpty()) {
            return ToolCallResult.failed("artifacts is required and must not be empty");
        }

        var submitted = new ArrayList<Map<String, Object>>();
        var failed = new ArrayList<Map<String, Object>>();
        for (var item : request.artifacts) {
            processArtifact(item, sandbox, submitted, failed);
        }

        var result = Map.of("submitted", submitted, "failed", failed);
        if (submitted.isEmpty()) {
            return ToolCallResult.failed(JsonUtil.toJson(result));
        }
        return ToolCallResult.completed(JsonUtil.toJson(result));
    }

    private void processArtifact(ArtifactRequest item, ai.core.sandbox.Sandbox sandbox,
                                 List<Map<String, Object>> submitted, List<Map<String, Object>> failed) {
        if (item == null || Strings.isBlank(item.path)) {
            failed.add(Map.of("path", "", "error", "path is required"));
            return;
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
            sink.append(artifact);

            var shared = fileService.share(record.id, userId);
            submitted.add(Map.of(
                "path", item.path,
                "file_id", record.id,
                "file_name", record.fileName,
                "download_url", publicUrlConfiguration.sharedArtifactDownloadUrl(shared.shareToken)
            ));
        } catch (Exception e) {
            failed.add(Map.of("path", item.path, "error", errorMessage(e)));
        }
    }

    private String errorMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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
