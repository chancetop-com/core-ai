package ai.core.server.gateway;

import java.util.List;

record GatewayModelMetadata(String id,
                            String displayName,
                            List<String> endpointTypes,
                            Long contextWindow,
                            Boolean supportsStream,
                            Boolean supportsTools,
                            Boolean supportsVision,
                            Double inputPricePer1MTokens,
                            Double outputPricePer1MTokens) {
}
