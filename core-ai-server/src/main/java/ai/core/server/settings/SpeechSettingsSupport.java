package ai.core.server.settings;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.server.domain.SystemSettings;
import ai.core.server.gateway.GatewayModelStore;
import ai.core.server.gateway.GatewaySecretProtector;
import core.framework.web.exception.BadRequestException;

/**
 * Speech-model settings are resolved per read, because the gateway model catalog changes without a
 * server restart. Kept out of SystemSettingsService to keep that class within its file-size budget.
 *
 * @author stephen
 */
final class SpeechSettingsSupport {
    static final String AZURE_SPEECH_MODEL = "azure-speech";

    static void validate(String model, String endpoint, SystemSettingsRequest request,
                         SystemSettings current, GatewayModelStore gatewayModelStore,
                         GatewaySecretProtector secretProtector) {
        if (model == null) return;
        if (AZURE_SPEECH_MODEL.equals(model)) {
            if (!azureSpeechConfigured(request, current, secretProtector)) {
                throw new BadRequestException("Azure Speech key and region are required for azure-speech");
            }
            return;
        }
        if (!gatewayModelStore.existsEnabledModelWithEndpoint(model, endpoint)) {
            throw new BadRequestException("speech model must be an enabled gateway model with " + endpoint + ": " + model);
        }
    }

    static String resolve(String model, String endpoint, SystemSettings entity,
                          GatewayModelStore gatewayModelStore, GatewaySecretProtector secretProtector) {
        if (model == null) return null;
        if (AZURE_SPEECH_MODEL.equals(model)) {
            return azureSpeechConfigured(entity, secretProtector) ? model : null;
        }
        return gatewayModelStore.existsEnabledModelWithEndpoint(model, endpoint) ? model : null;
    }

    private static boolean azureSpeechConfigured(SystemSettingsRequest request, SystemSettings current,
                                                 GatewaySecretProtector secretProtector) {
        var key = request.azureSpeechKey != null && !request.azureSpeechKey.isBlank()
                ? request.azureSpeechKey
                : current == null ? null : secretProtector.unprotect(current.azureSpeechKey);
        var region = request.azureSpeechRegion != null && !request.azureSpeechRegion.isBlank()
                ? request.azureSpeechRegion
                : current == null ? null : current.azureSpeechRegion;
        return hasText(key) && hasText(region);
    }

    private static boolean azureSpeechConfigured(SystemSettings entity, GatewaySecretProtector secretProtector) {
        if (entity == null) return false;
        return hasText(secretProtector.unprotect(entity.azureSpeechKey)) && hasText(entity.azureSpeechRegion);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private SpeechSettingsSupport() {
    }
}
