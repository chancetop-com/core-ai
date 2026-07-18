package ai.core.server.gateway;

enum GatewayEndpointType {
    CHAT_COMPLETIONS("/chat/completions", GatewayModelService.ENDPOINT_CHAT_COMPLETIONS),
    RESPONSES("/responses", GatewayModelService.ENDPOINT_RESPONSES),
    IMAGE_GENERATION("/images/generations", GatewayModelService.ENDPOINT_IMAGE_GENERATION),
    IMAGE_EDIT("/images/edits", GatewayModelService.ENDPOINT_IMAGE_EDIT),
    VIDEO_GENERATION("/videos", GatewayModelService.ENDPOINT_VIDEO_GENERATION);

    final String path;
    final String id;

    GatewayEndpointType(String path, String id) {
        this.path = path;
        this.id = id;
    }
}
