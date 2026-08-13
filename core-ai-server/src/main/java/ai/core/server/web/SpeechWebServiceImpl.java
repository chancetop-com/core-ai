package ai.core.server.web;

import ai.core.api.server.speech.SpeechTokenView;
import ai.core.api.server.speech.SpeechWebService;
import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.settings.SystemSettingsService;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class SpeechWebServiceImpl implements SpeechWebService {
    private final Logger logger = LoggerFactory.getLogger(SpeechWebServiceImpl.class);

    @Inject
    SystemSettingsService settingsService;

    private final HTTPClient httpClient = new PatchedHTTPClientBuilder().build();

    private String tokenUrl(String speechEndpoint, String speechRegion) {
        if (speechEndpoint != null && !speechEndpoint.isBlank()) {
            var base = speechEndpoint.endsWith("/") ? speechEndpoint : speechEndpoint + "/";
            return base + "sts/v1.0/issueToken";
        }
        return "https://" + speechRegion + ".api.cognitive.microsoft.com/sts/v1.0/issueToken";
    }

    @Override
    public SpeechTokenView getToken() {
        var speechKey = settingsService.azureSpeechKey();
        var speechRegion = settingsService.azureSpeechRegion();
        var speechEndpoint = settingsService.azureSpeechEndpoint();
        var region = speechRegion == null || speechRegion.isBlank() ? "eastus" : speechRegion;
        if (speechKey == null || speechKey.isBlank()) {
            throw new BadRequestException("Azure Speech service is not configured", "SPEECH_NOT_CONFIGURED");
        }
        try {
            var tokenRequest = new HTTPRequest(HTTPMethod.POST, tokenUrl(speechEndpoint, region));
            tokenRequest.headers.put("Ocp-Apim-Subscription-Key", speechKey);
            tokenRequest.headers.put("Content-Length", "0");
            var httpResponse = httpClient.execute(tokenRequest);
            if (httpResponse.statusCode >= 400) {
                logger.error("Azure Speech token exchange failed, status={}, body={}", httpResponse.statusCode, httpResponse.text());
                throw new BadRequestException("Failed to obtain speech token", "SPEECH_TOKEN_EXCHANGE_FAILED");
            }
            var view = new SpeechTokenView();
            view.token = httpResponse.text();
            view.region = region;
            return view;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Azure Speech token exchange error", e);
            throw new BadRequestException("Failed to obtain speech token", "SPEECH_TOKEN_EXCHANGE_FAILED", e);
        }
    }
}
