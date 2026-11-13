package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import core.framework.web.exception.BadRequestException;

import java.util.List;

/**
 * Google Cloud Speech is a transcription-only provider family; its credentials and GCP project are
 * validated before a model can be imported or created.
 *
 * @author stephen
 */
final class GatewaySpeechSupport {
    static boolean isGoogleSpeechProvider(GatewayProviderConfig provider) {
        return "google-speech".equals(provider.type) || "GOOGLE_SPEECH".equalsIgnoreCase(provider.mediaProtocol);
    }

    static void validateEndpointCompatibility(GatewayProviderConfig provider, List<String> endpointTypes) {
        if (!isGoogleSpeechProvider(provider)) return;
        if (GatewaySupport.isBlank(provider.vertexProjectId)) {
            throw new BadRequestException("google-speech provider requires a GCP project id (vertexProjectId), provider=" + provider.name);
        }
        for (var endpointType : endpointTypes) {
            if (!GatewayModelService.ENDPOINT_AUDIO_TRANSCRIPTION.equals(endpointType)) {
                throw new BadRequestException("google-speech provider supports audio.transcriptions only, provider="
                        + provider.name + ", endpoint=" + endpointType);
            }
        }
    }

    private GatewaySpeechSupport() {
    }
}
