package ai.core.server.gateway;

enum GatewayEndpointType {
    CHAT_COMPLETIONS("/chat/completions", GatewayModelService.ENDPOINT_CHAT_COMPLETIONS),
    RESPONSES("/responses", GatewayModelService.ENDPOINT_RESPONSES);

    final String path;
    final String id;

    GatewayEndpointType(String path, String id) {
        this.path = path;
        this.id = id;
    }
}
