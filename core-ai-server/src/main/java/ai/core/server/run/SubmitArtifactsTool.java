package ai.core.server.run;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.server.artifact.ArtifactSink;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.file.ArtifactPublisher;
import ai.core.server.file.FileService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import core.framework.api.json.Property;
import core.framework.util.Strings;
import core.framework.web.exception.NotFoundException;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

        Set `"public": true` on an artifact only when something outside this platform must fetch it: a URL you
        hand to a third-party API, a file embedded in a page served elsewhere, or a link shared beyond the
        conversation. The artifact is then stored in public object storage, and its `download_url` is a plain
        permanent URL that anyone holding it can open — so never mark confidential or user-private files public.
        Artifacts without the flag stay private and are delivered through the platform's share link.

        A file the platform already stores needs no sandbox copy: pass its `source` (the reference shown for an
        uploaded video, or a platform file URL) or its `file_id` instead of `path` — long videos are never staged.
        Use exactly one per item.

        This is a platform delivery requirement. It does not change the user's requested final response format:
        after submitting artifacts, still answer exactly as the task instructions require.
        """;

    private static final String TOOL_DESC = """
            Submit files created in the sandbox as downloadable run artifacts.

            Use this tool when you create files that the caller should download or reuse, such as reports,
            charts, PDFs, CSVs, images, spreadsheets, or archives. The files must already exist inside the
            sandbox, usually under /tmp or /workspace. Do not include file contents in this tool call; provide
            only the sandbox path and optional metadata.

            Each artifact also accepts `public: true` for files that must be reachable from outside the platform,
            such as a URL handed to a third-party API or embedded in a page served elsewhere. Those are stored in
            public object storage and their download_url is permanent and open to anyone who has it, so leave the
            flag off for anything confidential or meant only for this conversation's user.

            A file the platform already stores — an uploaded video or another file the user attached, or a file
            the platform produced earlier — can be published without a sandbox copy by passing its `source`
            (the reference shown in the user message) or its `file_id` instead of a `path`.
            """;

    private static final int MIN_REFERENCE_PREFIX_LENGTH = "video_".length() + 8;

    public static String appendInstructions(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) return SYSTEM_INSTRUCTIONS.strip();
        return systemPrompt + SYSTEM_INSTRUCTIONS;
    }

    public static SubmitArtifactsTool create(String userId, FileService fileService, ArtifactSink sink,
                                             PublicUrlConfiguration publicUrlConfiguration,
                                             SessionAttachmentRefRepository attachmentRepository) {
        var tool = new SubmitArtifactsTool(userId, fileService, sink, publicUrlConfiguration, attachmentRepository);
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
                Array of artifact objects. Each item MUST be a JSON object (not a bare string) with exactly one
                source selector: `path` (a sandbox file path), `file_id` (a platform file id), or `source`
                (a platform reference such as the `video_<uuid>` shown for an uploaded video, or a platform file
                URL). Optional object fields: name, title, description, content_type, public (set true only when
                an external system must fetch the file; it is then served from public object storage and the
                returned download_url is open to anyone who has it).
                Correct example: [{"path":"/tmp/report.pdf","name":"report.pdf","title":"Analysis","content_type":"application/pdf"}]
                Correct example for an uploaded video: [{"source":"video_1a2b3c4d-...","public":true}]
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
    private final ArtifactPublisher publisher;
    private final ArtifactSink sink;
    private final PublicUrlConfiguration publicUrlConfiguration;
    private final SessionAttachmentRefRepository attachmentRepository;

    private SubmitArtifactsTool(String userId, FileService fileService, ArtifactSink sink,
                                PublicUrlConfiguration publicUrlConfiguration,
                                SessionAttachmentRefRepository attachmentRepository) {
        this.userId = userId;
        this.fileService = fileService;
        this.publisher = fileService.publisher();
        this.sink = sink;
        this.publicUrlConfiguration = publicUrlConfiguration;
        this.attachmentRepository = attachmentRepository;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("submit_artifacts requires ExecutionContext; direct execute is not supported");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        if (context == null) {
            return ToolCallResult.failed("submit_artifacts requires ExecutionContext; direct execute is not supported");
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
            processArtifact(item, context, submitted, failed);
        }

        var result = Map.of("submitted", submitted, "failed", failed);
        if (submitted.isEmpty()) {
            return ToolCallResult.failed(JsonUtil.toJson(result));
        }
        return ToolCallResult.completed(JsonUtil.toJson(result));
    }

    private void processArtifact(ArtifactRequest item, ExecutionContext context,
                                 List<Map<String, Object>> submitted, List<Map<String, Object>> failed) {
        if (item == null) {
            failed.add(Map.of("error", "artifact item is required"));
            return;
        }
        try {
            var record = resolveRecord(item, context);

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
            var publicUrl = fileService.publicUrl(record);
            var entry = new LinkedHashMap<String, Object>();
            if (!Strings.isBlank(item.path)) entry.put("path", item.path);
            if (!Strings.isBlank(item.source)) entry.put("source", item.source);
            entry.put("file_id", record.id);
            entry.put("file_name", record.fileName);
            entry.put("download_url", publicUrl != null ? publicUrl : publicUrlConfiguration.sharedArtifactDownloadUrl(shared.shareToken));
            submitted.add(entry);
        } catch (Exception e) {
            var failure = new LinkedHashMap<String, Object>();
            if (!Strings.isBlank(item.path)) failure.put("path", item.path);
            if (!Strings.isBlank(item.fileId)) failure.put("file_id", item.fileId);
            if (!Strings.isBlank(item.source)) failure.put("source", item.source);
            failure.put("error", errorMessage(e));
            failed.add(failure);
        }
    }

    private FileRecord resolveRecord(ArtifactRequest item, ExecutionContext context) {
        var selectors = (Strings.isBlank(item.path) ? 0 : 1)
                + (Strings.isBlank(item.fileId) ? 0 : 1)
                + (Strings.isBlank(item.source) ? 0 : 1);
        if (selectors != 1) {
            throw new IllegalArgumentException("exactly one of path, file_id or source is required");
        }

        var publicAccess = Boolean.TRUE.equals(item.publicAccess);
        if (!Strings.isBlank(item.path)) {
            var sandbox = context.getSandbox();
            if (sandbox == null) throw new IllegalStateException("submit_artifacts requires an active sandbox to submit a path");
            return uploadSandboxFile(item, sandbox, publicAccess);
        }
        if (!Strings.isBlank(item.fileId)) {
            return publisher.publish(fileService.getOwned(item.fileId, userId), publicAccess);
        }
        return publishSource(item, context, publicAccess);
    }

    private FileRecord uploadSandboxFile(ArtifactRequest item, Sandbox sandbox, boolean publicAccess) {
        var sandboxFile = sandbox.downloadFile(item.path);
        var fileName = !Strings.isBlank(item.name) ? item.name : sandboxFile.fileName();
        var contentType = !Strings.isBlank(item.contentType) ? item.contentType : sandboxFile.contentType();
        // Reuse an existing record when the user already has a file with identical content,
        // so platform-saved media (e.g. get_video_status auto-save) is not duplicated as artifacts.
        return fileService.uploadIfAbsent(userId, fileName, contentType, sandboxFile.path(), publicAccess);
    }

    /**
     * Publishes a file that is already in platform storage: an attachment of this session (the reference the
     * message shows for an uploaded video, which is never staged when the file is large) or a platform file URL
     * the agent was handed. The blob is copied into an artifact container rather than downloaded through the
     * sandbox, so the size of the staging limit does not matter here.
     */
    private FileRecord publishSource(ArtifactRequest item, ExecutionContext context, boolean publicAccess) {
        var reference = findAttachmentReference(item.source, context);
        if (reference != null) {
            var fileName = !Strings.isBlank(item.name) ? item.name : reference.fileName;
            var contentType = !Strings.isBlank(item.contentType) ? item.contentType : reference.contentType;
            return publisher.copyToArtifact(userId, fileName, contentType,
                    reference.container, reference.blobName, publicAccess);
        }

        var record = findPlatformFile(item.source);
        if (record != null) {
            return publisher.publish(record, publicAccess);
        }
        throw new IllegalArgumentException("source is not a file of this platform: " + item.source);
    }

    private SessionAttachmentRef findAttachmentReference(String source, ExecutionContext context) {
        if (attachmentRepository == null || context.getSessionId() == null || userId == null) return null;
        var exact = attachmentRepository.findOwned(source, context.getSessionId(), userId);
        if (usable(exact)) return exact;
        if (source.length() < MIN_REFERENCE_PREFIX_LENGTH) return null;
        // models routinely abbreviate video_<uuid> to its first hex group; a unique prefix still resolves
        var matches = attachmentRepository.findOwnedByPrefix(source, context.getSessionId(), userId).stream()
                .filter(this::usable).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean usable(SessionAttachmentRef reference) {
        return reference != null && !Strings.isBlank(reference.container) && !Strings.isBlank(reference.blobName);
    }

    private FileRecord findPlatformFile(String source) {
        var path = platformPath(source);
        if (path == null) return null;
        try {
            if (path.startsWith("/api/files/")) {
                var id = path.substring("/api/files/".length());
                var slash = id.indexOf('/');
                if (slash <= 0 || !"content".equals(id.substring(slash + 1))) return null;
                return fileService.getOwned(id.substring(0, slash), userId);
            }
            if (path.startsWith("/api/public/artifacts/")) {
                var token = path.substring("/api/public/artifacts/".length());
                var slash = token.indexOf('/');
                if (slash <= 0 || !"content".equals(token.substring(slash + 1))) return null;
                var record = fileService.getShared(token.substring(0, slash));
                return Objects.equals(record.userId, userId) ? record : null;
            }
        } catch (NotFoundException e) {
            return null;
        }
        return null;
    }

    /** The path part of a platform URL (absolute or relative), without query or fragment. */
    private String platformPath(String source) {
        var path = source;
        var scheme = source.indexOf("://");
        if (scheme >= 0) {
            var start = source.indexOf('/', scheme + 3);
            if (start < 0) return null;
            path = source.substring(start);
        }
        var end = path.length();
        var query = path.indexOf('?');
        if (query >= 0) end = query;
        var fragment = path.indexOf('#');
        if (fragment >= 0 && fragment < end) end = fragment;
        path = path.substring(0, end);
        return path.startsWith("/") ? path : null;
    }

    private String errorMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    public static class Request {
        public List<ArtifactRequest> artifacts;
    }

    public static class ArtifactRequest {
        public String path;
        @Property(name = "file_id")
        public String fileId;
        public String source;
        public String name;
        public String title;
        public String description;
        @Property(name = "content_type")
        public String contentType;
        @Property(name = "public")
        public Boolean publicAccess;
    }
}
