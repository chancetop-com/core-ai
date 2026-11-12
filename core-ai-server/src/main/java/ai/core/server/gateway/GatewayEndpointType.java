package ai.core.server.gateway;

import java.util.Locale;
import java.util.Map;

public enum GatewayEndpointType {
    CHAT_COMPLETIONS("/chat/completions", GatewayModelService.ENDPOINT_CHAT_COMPLETIONS),
    RESPONSES("/responses", GatewayModelService.ENDPOINT_RESPONSES),
    MESSAGES("/messages", GatewayModelService.ENDPOINT_MESSAGES),
    IMAGE_GENERATION("/images/generations", GatewayModelService.ENDPOINT_IMAGE_GENERATION),
    IMAGE_EDIT("/images/edits", GatewayModelService.ENDPOINT_IMAGE_EDIT),
    VIDEO_GENERATION("/videos", GatewayModelService.ENDPOINT_VIDEO_GENERATION),
    AUDIO_TRANSCRIPTION("/audio/transcriptions", GatewayModelService.ENDPOINT_AUDIO_TRANSCRIPTION),
    DECISION("/systemone", GatewayModelService.ENDPOINT_DECISION);

    private static final Map<String, GatewayEndpointType> ALIASES = Map.ofEntries(
            Map.entry("chat", CHAT_COMPLETIONS),
            Map.entry("chat.completion", CHAT_COMPLETIONS),
            Map.entry("chat.completions", CHAT_COMPLETIONS),
            Map.entry("response", RESPONSES),
            Map.entry("responses", RESPONSES),
            Map.entry("message", MESSAGES),
            Map.entry("messages", MESSAGES),
            Map.entry("image", IMAGE_GENERATION),
            Map.entry("image.generation", IMAGE_GENERATION),
            Map.entry("image.generations", IMAGE_GENERATION),
            Map.entry("image.edit", IMAGE_EDIT),
            Map.entry("image.edits", IMAGE_EDIT),
            Map.entry("video", VIDEO_GENERATION),
            Map.entry("video.generation", VIDEO_GENERATION),
            Map.entry("video.generations", VIDEO_GENERATION),
            Map.entry("audio", AUDIO_TRANSCRIPTION),
            Map.entry("audio.transcription", AUDIO_TRANSCRIPTION),
            Map.entry("audio.transcriptions", AUDIO_TRANSCRIPTION),
            Map.entry("transcription", AUDIO_TRANSCRIPTION),
            Map.entry("transcribe", AUDIO_TRANSCRIPTION),
            Map.entry("stt", AUDIO_TRANSCRIPTION),
            Map.entry("decision", DECISION),
            Map.entry("decision.systemone", DECISION),
            Map.entry("systemone", DECISION)
    );

    static GatewayEndpointType fromAlias(String value) {
        return ALIASES.get(value.toLowerCase(Locale.ROOT));
    }

    final String path;
    final String id;

    GatewayEndpointType(String path, String id) {
        this.path = path;
        this.id = id;
    }
}
