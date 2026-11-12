package ai.core.cli.speech;

import ai.core.api.server.speech.ListSpeechModelsResponse;
import ai.core.api.server.speech.SpeechTranscriptionView;
import ai.core.cli.http.RemoteApiClient;
import ai.core.tool.tools.TranscribeAudioTool;
import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Typed client over {@code /api/speech/models} and {@code /api/speech/transcriptions}. Both the
 * {@code speech} command and the {@code transcribe_audio} builtin tool use it, so the CLI and the
 * desktop engine transcribe through exactly the same server path.
 *
 * @author stephen
 */
public class SpeechClient implements TranscribeAudioTool.AudioTranscriptionService {
    private static final Duration TRANSCRIBE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(8);
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final long MAX_AUDIO_BYTES = 9L * 1024 * 1024;
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("wav", "audio/wav"), Map.entry("wave", "audio/wav"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("mpeg", "audio/mpeg"),
            Map.entry("m4a", "audio/mp4"), Map.entry("mp4", "audio/mp4"),
            Map.entry("ogg", "audio/ogg"), Map.entry("opus", "audio/ogg"),
            Map.entry("webm", "audio/webm"), Map.entry("flac", "audio/flac"),
            Map.entry("aac", "audio/aac"));

    public static String contentType(Path audio) {
        var name = fileName(audio).toLowerCase(Locale.ROOT);
        var dot = name.lastIndexOf('.');
        var extension = dot >= 0 ? name.substring(dot + 1) : "";
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static String fileName(Path audio) {
        var path = audio.getFileName();
        return path == null ? "" : path.toString();
    }

    private final RemoteApiClient client;
    private final RemoteApiClient modelClient;

    public SpeechClient(String serverUrl, String apiKey, boolean insecure) {
        var headers = Map.of(CLIENT_HEADER, "cli");
        this.client = new RemoteApiClient(serverUrl, apiKey, TRANSCRIBE_TIMEOUT, headers, insecure);
        // catalog reads happen on the engine dispatch thread during connect: never let a dead server hold it
        this.modelClient = new RemoteApiClient(serverUrl, apiKey, MODEL_TIMEOUT, headers, insecure);
    }

    public ListSpeechModelsResponse models() {
        return JsonUtil.fromJson(ListSpeechModelsResponse.class,
                modelClient.getRequired("/api/speech/models"));
    }

    @Override
    public SpeechTranscriptionView transcribe(Path audio, String language, String model, String prompt) {
        checkSize(audio);
        var fields = new LinkedHashMap<String, String>();
        put(fields, "model", model);
        put(fields, "language", language);
        put(fields, "prompt", prompt);
        var fileName = fileName(audio);
        var file = new RemoteApiClient.MultipartFile(audio, fileName.isEmpty() ? "audio" : fileName, contentType(audio));
        var body = client.postMultipartRequired("/api/speech/transcriptions", fields, Map.of("audio", file));
        return JsonUtil.fromJson(SpeechTranscriptionView.class, body);
    }

    private void put(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) fields.put(key, value.trim());
    }

    /** Server requests are capped at 10 MB including multipart overhead; fail locally with a clearer message. */
    private void checkSize(Path audio) {
        try {
            if (Files.size(audio) > MAX_AUDIO_BYTES) {
                throw new IllegalArgumentException("audio file is too large (max 9 MB): " + audio);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read audio file: " + audio, e);
        }
    }
}
