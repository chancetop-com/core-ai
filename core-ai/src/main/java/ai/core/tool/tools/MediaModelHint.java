package ai.core.tool.tools;

/**
 * A gateway media model visible to the generate_image / generate_video tools, used to build
 * the dynamic tool description.
 *
 * @param modelId the gateway alias the agent passes in the model parameter
 * @param upstreamModel the provider model name (e.g. bytedance/seedance-2)
 * @param providerName the gateway provider name, or null
 * @author stephen
 */
public record MediaModelHint(String modelId, String upstreamModel, String providerName) {
}
