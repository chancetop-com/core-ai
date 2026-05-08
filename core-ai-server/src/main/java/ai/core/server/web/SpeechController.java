package ai.core.server.web;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.web.Request;
import core.framework.web.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class SpeechController {
    private final Logger logger = LoggerFactory.getLogger(SpeechController.class);

    public String speechKey;
    public String speechRegion;
    public String speechEndpoint;  // e.g. https://xxx.cognitiveservices.azure.com/

    private final HTTPClient httpClient = new PatchedHTTPClientBuilder().build();

    private String tokenUrl() {
        if (speechEndpoint != null && !speechEndpoint.isBlank()) {
            var base = speechEndpoint.endsWith("/") ? speechEndpoint : speechEndpoint + "/";
            return base + "sts/v1.0/issueToken";
        }
        return "https://" + speechRegion + ".api.cognitive.microsoft.com/sts/v1.0/issueToken";
    }

    public Response getToken(Request request) {
        if (speechKey == null || speechKey.isBlank()) {
            return Response.text("Azure Speech service is not configured").status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }
        try {
            var tokenRequest = new HTTPRequest(HTTPMethod.POST, tokenUrl());
            tokenRequest.headers.put("Ocp-Apim-Subscription-Key", speechKey);
            tokenRequest.headers.put("Content-Length", "0");
            var httpResponse = httpClient.execute(tokenRequest);
            if (httpResponse.statusCode >= 400) {
                logger.error("Azure Speech token exchange failed, status={}, body={}", httpResponse.statusCode, httpResponse.text());
                return Response.text("Failed to obtain speech token").status(HTTPStatus.INTERNAL_SERVER_ERROR);
            }
            var token = httpResponse.text();
            var json = String.format("{\"token\":\"%s\",\"region\":\"%s\"}", token, speechRegion);
            return Response.bytes(json.getBytes(StandardCharsets.UTF_8)).contentType(ContentType.APPLICATION_JSON);
        } catch (Exception e) {
            logger.error("Azure Speech token exchange error", e);
            return Response.text("Failed to obtain speech token").status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
