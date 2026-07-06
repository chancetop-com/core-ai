package ai.core.server.gateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

final class GatewayJson {
    // shared mapper: unknown fields must not fail requests, so frontend can ship new fields before backend deploys
    static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private GatewayJson() {
    }
}
