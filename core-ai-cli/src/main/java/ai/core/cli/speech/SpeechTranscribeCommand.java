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

    @Option(names = "--output", description = "Write the transcript to this file instead of stdout")
    Path output;

    @Override
    protected Integer execute() {
        var file = audioFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new HubCliError(HubExitCodes.USAGE, "audio file not found: " + file);
        }
        var credentials = credentials();
        var client = new SpeechClient(credentials.serverUrl(), credentials.apiKey(), credentials.insecure());
        var result = client.transcribe(file, language, model, prompt);
        if (json()) {
            HubRenderer.printJson(result);
            return HubExitCodes.SUCCESS;
        }
        if (output != null) {
            try {
                Files.writeString(output, result.text == null ? "" : result.text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new HubCliError(HubExitCodes.TOOL_ERROR, "failed to write transcript: " + e.getMessage(), e);
            }
        } else {
            ConsoleWriter.print(result.text == null ? "" : result.text);
            if (result.text != null && !result.text.endsWith("\n")) ConsoleWriter.println();
        }
        metadata(metadata(result));
        return HubExitCodes.SUCCESS;
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
