package ai.core.tool.tools;

/**
 * A gateway video model visible to the generate_video tool, used to build the dynamic tool description.
 *
 * @param modelId the gateway alias the agent passes in the model parameter
 * @param upstreamModel the provider model name (e.g. bytedance/seedance-2)
 * @param providerName the gateway provider name, or null
 * @author stephen
 */
public record VideoModelHint(String modelId, String upstreamModel, String providerName) {
}
