package ai.core.cli.speech;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * @author stephen
 */
@Command(name = "synthesize", description = "Synthesize speech from text with the server's TTS models")
class SpeechSynthesizeCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "text", description = "Text to speak")
    String text;

    @Option(names = "--model", description = "TTS model id (see 'core-ai-cli speech models --synthesis'); omit for the default")
    String model;

    @Option(names = "--voice", description = "Provider voice id (e.g. alloy, zh-CN-XiaoxiaoNeural)")
    String voice;

    @Option(names = "--language", description = "Language hint (BCP-47)")
    String language;

    @Option(names = "--format", description = "Audio format: mp3 (default), wav, opus, aac, flac")
    String format;

    @Option(names = "--speed", description = "Speaking speed multiplier")
    Double speed;

    @Option(names = "--output", paramLabel = "file", description = "Write audio to this file (default: speech.<format>)")
    Path output;

    @Override
    protected Integer execute() {
        if (text == null || text.isBlank()) {
            throw new HubCliError(HubExitCodes.USAGE, "text is required");
        }
        var credentials = credentials();
        var audio = new SpeechClient(credentials.serverUrl(), credentials.apiKey(), credentials.insecure())
                .synthesize(text, model, voice, language, format, speed);
        var target = output == null ? Path.of("speech." + effectiveFormat()) : output.toAbsolutePath().normalize();
        try {
            var parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(target, audio.audio());
        } catch (IOException e) {
            throw new HubCliError(HubExitCodes.TOOL_ERROR, "failed to write speech audio: " + e.getMessage(), e);
        }
        if (json()) {
            var result = new LinkedHashMap<String, Object>();
            result.put("output", target.toString());
            result.put("bytes", audio.audio().length);
            result.put("contentType", audio.contentType());
            result.put("model", audio.model());
            result.put("provider", audio.provider());
            result.put("voice", audio.voice());
            HubRenderer.printJson(result);
        } else {
            metadata("speech: model=" + value(audio.model()) + ", provider=" + value(audio.provider())
                    + ", voice=" + value(audio.voice()) + ", bytes=" + audio.audio().length);
            ConsoleWriter.println(target.toString());
        }
        return HubExitCodes.SUCCESS;
    }

    private String effectiveFormat() {
        return format == null || format.isBlank() ? "mp3" : format.trim().toLowerCase(Locale.ROOT);
    }

    private String value(String value) {
        return value == null ? "-" : value;
    }
}
