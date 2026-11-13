package ai.core.cli.speech;

import ai.core.api.server.speech.SpeechTranscriptionView;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Transcribes one local audio file. The transcript goes to stdout (or --output); model, provider and
 * duration go to stderr so shell pipelines keep a clean text stream.
 *
 * @author stephen
 */
@Command(name = "transcribe", description = "Transcribe a local audio file to text")
class SpeechTranscribeCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "audio-file", description = "Audio file (wav, mp3, m4a, ogg, webm, flac)")
    Path audioFile;

    @Option(names = "--language", description = "Language hint: BCP-47 (zh-CN) or ISO-639-1 (zh); gateway models "
            + "auto-detect when omitted, Azure falls back to zh-CN")
    String language;

    @Option(names = "--model", description = "Speech model id (see 'core-ai-cli speech models'); omit for the default")
    String model;

    @Option(names = "--prompt", description = "Optional hint for words or names the recognizer should expect")
    String prompt;

    @Option(names = "--format", paramLabel = "text|segments|json", description = "Output format: text (default), "
            + "segments (timestamp and speaker per phrase), or json (full response)")
    String format;

    @Option(names = "--output", description = "Write the transcript to this file instead of stdout")
    Path output;

    @Override
    protected Integer execute() {
        var file = audioFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new HubCliError(HubExitCodes.USAGE, "audio file not found: " + file);
        }
        var effectiveFormat = format == null ? "text" : format.toLowerCase(Locale.ROOT);
        if (!Set.of("text", "segments", "json").contains(effectiveFormat)) {
            throw new HubCliError(HubExitCodes.USAGE, "unsupported --format: " + format + " (use text, segments, or json)");
        }
        var credentials = credentials();
        var client = new SpeechClient(credentials.serverUrl(), credentials.apiKey(), credentials.insecure());
        var result = client.transcribe(file, language, model, prompt);
        if (json() || "json".equals(effectiveFormat)) {
            HubRenderer.printJson(result);
            return HubExitCodes.SUCCESS;
        }
        var rendered = "segments".equals(effectiveFormat) ? renderSegments(result) : text(result);
        if (output != null) {
            try {
                Files.writeString(output, rendered, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new HubCliError(HubExitCodes.TOOL_ERROR, "failed to write transcript: " + e.getMessage(), e);
            }
        } else {
            ConsoleWriter.print(rendered);
            if (!rendered.endsWith("\n")) ConsoleWriter.println();
        }
        metadata(metadata(result));
        return HubExitCodes.SUCCESS;
    }

    private String text(SpeechTranscriptionView result) {
        return result.text == null ? "" : result.text;
    }

    private String renderSegments(SpeechTranscriptionView result) {
        if (result.segments == null || result.segments.isEmpty()) return text(result);
        var builder = new StringBuilder();
        for (var segment : result.segments) {
            if (segment == null || segment.text == null || segment.text.isBlank()) continue;
            builder.append('[').append(timestamp(segment.start)).append(']');
            if (segment.speaker != null && !segment.speaker.isBlank()) {
                builder.append(" Speaker ").append(segment.speaker).append(':');
            }
            builder.append(' ').append(segment.text.strip()).append('\n');
        }
        return builder.length() == 0 ? text(result) : builder.toString();
    }

    private String timestamp(Double seconds) {
        var total = seconds == null || seconds < 0 ? 0 : (long) seconds.doubleValue();
        return String.format("%02d:%02d:%02d", total / 3600, total % 3600 / 60, total % 60);
    }

    private String metadata(SpeechTranscriptionView result) {
        var builder = new StringBuilder(160).append("speech:");
        if (result.model != null) builder.append(" model=").append(result.model);
        if (result.provider != null) builder.append(", provider=").append(result.provider);
        if (result.language != null) builder.append(", language=").append(result.language);
        if (result.durationSeconds != null) builder.append(", duration=").append(String.format("%.1fs", result.durationSeconds));
        return builder.toString();
    }
}
