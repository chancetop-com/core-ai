package ai.core.server.audio;

import ai.core.media.audio.AsrResult;
import ai.core.media.audio.AsrSpec;
import ai.core.media.audio.AudioProvider;
import ai.core.media.audio.TtsResult;
import ai.core.media.audio.TtsSpec;
import ai.core.media.audio.WordTimestamp;
import ai.core.server.settings.SystemSettingsService;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Azure Speech over REST, reusing the existing SystemSettings credentials (design §5). REST TTS has
 * no word-boundary events (SDK/websocket only) — TtsResult.words is empty; the pipeline transcribes
 * synthesized narration through the same ASR path used for clip dialogue, so subtitles get one
 * consistent timestamp source. ASR uses the fast transcription API (word-level offsets).
 *
 * @author stephen
 */
public class AzureSpeechAudioProvider implements AudioProvider {
    private static final String TTS_OUTPUT_FORMAT = "audio-48khz-96kbitrate-mono-mp3";
    private final HTTPClient httpClient = HTTPClient.builder()
        .connectTimeout(Duration.ofSeconds(10))
        .timeout(Duration.ofMinutes(3))
        .build();

    @Inject
    SystemSettingsService settingsService;

    @Override
    public TtsResult synthesize(TtsSpec spec) {
        var region = requireRegion();
        var request = new HTTPRequest(HTTPMethod.POST, "https://" + region + ".tts.speech.microsoft.com/cognitiveservices/v1");
        request.headers.put("Ocp-Apim-Subscription-Key", requireKey());
        request.headers.put("X-Microsoft-OutputFormat", TTS_OUTPUT_FORMAT);
        request.body(ssml(spec).getBytes(StandardCharsets.UTF_8), ContentType.parse("application/ssml+xml"));
        var response = httpClient.execute(request);
        if (response.statusCode < 200 || response.statusCode >= 300)
            throw new BadRequestException("azure tts failed: HTTP " + response.statusCode + " " + new String(response.body, StandardCharsets.UTF_8));
        return new TtsResult(response.body, "audio/mpeg", List.of());
    }

    @Override
    public AsrResult transcribe(AsrSpec spec) {
        var region = requireRegion();
        var boundary = "----asr" + System.nanoTime();
        var request = new HTTPRequest(HTTPMethod.POST,
            "https://" + region + ".api.cognitive.microsoft.com/speechtotext/transcriptions:transcribe?api-version=2024-11-15");
        request.headers.put("Ocp-Apim-Subscription-Key", requireKey());
        request.body(multipart(boundary, spec), ContentType.parse("multipart/form-data; boundary=" + boundary));
        var response = httpClient.execute(request);
        var body = new String(response.body, StandardCharsets.UTF_8);
        if (response.statusCode < 200 || response.statusCode >= 300)
            throw new BadRequestException("azure fast transcription failed: HTTP " + response.statusCode + " " + body);
        return parseTranscription(body);
    }

    /** Builds SSML honoring voiceId/style/speed/pitch unless caller supplied full SSML already. */
    String ssml(TtsSpec spec) {
        if (spec.ssml() != null && !spec.ssml().isBlank()) return spec.ssml();
        var locale = spec.locale() == null ? "zh-CN" : spec.locale();
        var voice = spec.voiceId() == null ? "zh-CN-XiaoxiaoNeural" : spec.voiceId();
        var builder = new StringBuilder(512);
        builder.append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xmlns:mstts='https://www.w3.org/2001/mstts' xml:lang='")
            .append(locale).append("'><voice name='").append(voice).append("'>");
        var hasStyle = spec.style() != null && !spec.style().isBlank();
        if (hasStyle) builder.append("<mstts:express-as style='").append(spec.style()).append("'>");
        var hasProsody = spec.speed() != null || spec.pitch() != null;
        if (hasProsody) {
            builder.append("<prosody");
            if (spec.speed() != null) builder.append(" rate='").append(Math.round((spec.speed() - 1) * 100)).append("%'");
            if (spec.pitch() != null) builder.append(" pitch='").append(Math.round((spec.pitch() - 1) * 100)).append("%'");
            builder.append('>');
        }
        builder.append(escapeXml(spec.text()));
        if (hasProsody) builder.append("</prosody>");
        if (hasStyle) builder.append("</mstts:express-as>");
        builder.append("</voice></speak>");
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    AsrResult parseTranscription(String json) {
        var root = JsonUtil.toMap(json);
        var text = "";
        if (root.get("combinedPhrases") instanceof List<?> combined && !combined.isEmpty() && combined.getFirst() instanceof Map<?, ?> first) {
            text = String.valueOf(first.get("text"));
        }
        var words = new ArrayList<WordTimestamp>();
        if (root.get("phrases") instanceof List<?> phrases) {
            for (var phraseObj : phrases) {
                if (!(phraseObj instanceof Map<?, ?> phrase) || !(phrase.get("words") instanceof List<?> phraseWords)) continue;
                for (var wordObj : phraseWords) {
                    if (!(wordObj instanceof Map<?, ?> word)) continue;
                    var offset = millis(word.get("offsetMilliseconds"));
                    var duration = millis(word.get("durationMilliseconds"));
                    words.add(new WordTimestamp(String.valueOf(word.get("text")), offset, offset + duration));
                }
            }
        }
        return new AsrResult(text, words);
    }

    private double millis(Object value) {
        return value instanceof Number number ? number.doubleValue() / 1000 : 0;
    }

    private byte[] multipart(String boundary, AsrSpec spec) {
        var locale = spec.locale() == null ? "zh-CN" : spec.locale();
        var definition = "{\"locales\":[\"" + locale + "\"]}";
        var head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"definition\"\r\n\r\n" + definition + "\r\n"
            + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"audio.bin\"\r\n"
            + "Content-Type: " + (spec.contentType() == null ? "application/octet-stream" : spec.contentType()) + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
        var tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var body = new byte[head.length + spec.audio().length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(spec.audio(), 0, body, head.length, spec.audio().length);
        System.arraycopy(tail, 0, body, head.length + spec.audio().length, tail.length);
        return body;
    }

    private String requireKey() {
        var key = settingsService.azureSpeechKey();
        if (key == null || key.isBlank()) throw new BadRequestException("Azure Speech key is not configured in system settings");
        return key;
    }

    private String requireRegion() {
        var region = settingsService.azureSpeechRegion();
        if (region == null || region.isBlank()) throw new BadRequestException("Azure Speech region is not configured in system settings");
        return region;
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;");
    }
}
