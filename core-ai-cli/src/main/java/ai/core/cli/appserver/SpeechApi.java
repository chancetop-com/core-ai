package ai.core.cli.appserver;

import ai.core.api.server.speech.SpeechTranscriptionView;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.speech.SpeechClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * App-server surface for the desktop composer: the speech model catalog and one-shot clip
 * transcription. Credentials stay in the engine; the renderer only sends base64 audio.
 *
 * @author stephen
 */
class SpeechApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpeechApi.class);
    private static final int MAX_AUDIO_BYTES = 9 * 1024 * 1024;

    /**
     * Not being signed in is a normal state (the mic button disables itself), so this returns an
     * empty catalog instead of an RPC error.
     */
    ObjectNode models() {
        var node = Params.object();
        var auth = AuthConfig.load();
        var signedIn = auth != null && auth.serverUrl() != null && auth.apiKey() != null;
        node.put("signedIn", signedIn);
        var models = node.putArray("models");
        if (!signedIn) return node;
        try {
            var response = new SpeechClient(auth.serverUrl(), auth.apiKey(), auth.insecure()).models();
            if (response.models != null) {
                for (var model : response.models) {
                    var item = models.addObject();
                    item.put("modelId", model.modelId);
                    if (model.displayName != null) item.put("displayName", model.displayName);
                    if (model.providerName != null) item.put("providerName", model.providerName);
                }
            }
            if (response.defaultModel != null) node.put("defaultModel", response.defaultModel);
            node.put("azureConfigured", Boolean.TRUE.equals(response.azureConfigured));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to fetch speech models: {}", e.getMessage());
            node.put("error", e.getMessage());
        }
        return node;
    }

    ObjectNode transcribe(ObjectNode params) {
        var auth = AuthConfig.load();
        if (auth == null || auth.serverUrl() == null || auth.apiKey() == null) {
            throw RpcException.business("NOT_SIGNED_IN", "login is required for speech transcription");
        }
        var audio = decode(Params.requiredText(params, "audio"));
        if (audio.length == 0) throw RpcException.invalidParams("audio is empty");
        if (audio.length > MAX_AUDIO_BYTES) throw RpcException.invalidParams("audio exceeds the 25 MB limit");
        var temp = stage(audio, params.path("fileName").asText("voice.wav"));
        try {
            var view = new SpeechClient(auth.serverUrl(), auth.apiKey(), auth.insecure()).transcribe(
                    temp, Params.optionalText(params, "language"), Params.optionalText(params, "model"),
                    Params.optionalText(params, "prompt"));
            return view(view);
        } finally {
            deleteQuietly(temp);
        }
    }

    private byte[] decode(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams("audio must be base64-encoded", e);
        }
    }

    private Path stage(byte[] audio, String fileName) {
        try {
            var temp = Files.createTempFile("coreai-voice-", suffix(fileName));
            Files.write(temp, audio);
            return temp;
        } catch (IOException e) {
            throw RpcException.business("SPEECH_IO_FAILED", "failed to stage audio: " + e.getMessage(), e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("failed to delete speech temp file {}: {}", path, e.getMessage());
        }
    }

    private ObjectNode view(SpeechTranscriptionView view) {
        var node = Params.object();
        node.put("text", view.text);
        if (view.language != null) node.put("language", view.language);
        if (view.durationSeconds != null) node.put("durationSeconds", view.durationSeconds);
        if (view.model != null) node.put("model", view.model);
        if (view.provider != null) node.put("provider", view.provider);
        if (view.segments != null) {
            var segments = node.putArray("segments");
            for (var segment : view.segments) {
                var item = segments.addObject();
                if (segment.text != null) item.put("text", segment.text);
                if (segment.start != null) item.put("start", segment.start);
                if (segment.end != null) item.put("end", segment.end);
            }
        }
        return node;
    }

    private String suffix(String fileName) {
        var dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || fileName.length() - dot > 8) return ".wav";
        return fileName.substring(dot);
    }
}
