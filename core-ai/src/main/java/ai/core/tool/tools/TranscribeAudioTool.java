package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.speech.SpeechTranscriptionView;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Transcribes a local audio file through the server's speech endpoint. The service is the CLI's
 * {@code SpeechClient}, so the agent and the {@code speech} command share one model catalog and one
 * credential.
 *
 * @author stephen
 */
public final class TranscribeAudioTool extends ToolCall {
    public static final String TOOL_NAME = "transcribe_audio";
    private static final long MAX_AUDIO_BYTES = 9L * 1024 * 1024;

    private static final String DESCRIPTION = """
            Transcribe an audio recording to text with the configured speech-to-text model.
            Use this tool for meetings, interviews, voice notes, podcasts and any audio file whose words matter.
            IMPORTANT:
            - file must be a local path (absolute, or relative to the workspace). Do not pass a URL or a media_id.
            - The audio is uploaded to the server's speech endpoint; supported formats include wav, mp3, m4a, ogg and webm.
            - When the user needs timestamps, set timestamps=true; segments are rendered as [mm:ss] lines.
            - Do not read the audio bytes with read_file or shell first. Only this tool can turn speech into text.
            """;

    public static Builder builder(AudioTranscriptionService service) {
        return new Builder().service(service);
    }

    private final AudioTranscriptionService service;

    private TranscribeAudioTool(AudioTranscriptionService service) {
        this.service = service;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("transcribe_audio requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        if (service == null) return ToolCallResult.failed("speech transcription is not configured for this client");
        try {
            var params = JsonUtil.fromJson(TranscribeAudioParams.class, arguments);
            if (params.file() == null || params.file().isBlank()) return ToolCallResult.failed("file is required");
            var file = resolve(params.file(), context);
            if (!Files.isRegularFile(file)) return ToolCallResult.failed("audio file not found: " + file);
            if (Files.size(file) > MAX_AUDIO_BYTES) {
                return ToolCallResult.failed("audio file is too large (max 9 MB): " + file);
            }
            var result = service.transcribe(file, params.language(), params.model(), params.prompt());
            var output = Boolean.TRUE.equals(params.timestamps()) && !result.segments.isEmpty()
                    ? withTimestamps(result) : result.text;
            var completed = ToolCallResult.completed(output);
            if (result.model != null) completed.withStats("speech_model", result.model);
            if (result.provider != null) completed.withStats("speech_provider", result.provider);
            if (result.language != null) completed.withStats("speech_language", result.language);
            return completed;
        } catch (Exception e) {
            return ToolCallResult.failed("audio transcription failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    private Path resolve(String value, ExecutionContext context) {
        var path = Path.of(value);
        if (path.isAbsolute()) return path;
        var workspace = context.getCustomVariable("workspace", String.class);
        var base = workspace == null || workspace.isBlank() ? System.getProperty("user.dir") : workspace;
        return Path.of(base).resolve(path).normalize();
    }

    private String withTimestamps(SpeechTranscriptionView result) {
        var builder = new StringBuilder();
        for (var segment : result.segments) {
            if (segment.text == null || segment.text.isBlank()) continue;
            builder.append('[').append(timestamp(segment.start)).append("] ").append(segment.text.trim()).append('\n');
        }
        return builder.isEmpty() ? result.text : builder.toString().trim();
    }

    private String timestamp(Double seconds) {
        var total = seconds == null ? 0 : Math.max(0, Math.round(seconds));
        var minutes = total / 60;
        var rest = total % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, rest);
    }

    public interface AudioTranscriptionService {
        SpeechTranscriptionView transcribe(Path file, String language, String model, String prompt);
    }

    public static class Builder extends ToolCall.Builder<Builder, TranscribeAudioTool> {
        private AudioTranscriptionService service;

        public Builder service(AudioTranscriptionService service) {
            this.service = service;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public TranscribeAudioTool build() {
            name(TOOL_NAME);
            description(DESCRIPTION);
            timeoutMs(600_000L);
            parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "file",
                            "Local audio file path (absolute or workspace-relative). Required.").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "language",
                            "Optional language hint: BCP-47 (zh-CN) or ISO-639-1 (zh); omit to auto-detect"),
                    ToolCallParameters.ParamSpec.of(String.class, "model",
                            "Optional speech model id from the server's speech model catalog"),
                    ToolCallParameters.ParamSpec.of(String.class, "prompt",
                            "Optional hint for words or names the recognizer should expect"),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "timestamps",
                            "Render segment timestamps as [mm:ss] lines; default false")
            ));
            var tool = new TranscribeAudioTool(service);
            build(tool);
            return tool;
        }
    }

    public record TranscribeAudioParams(String file, String language, String model, String prompt, Boolean timestamps) {
    }
}
