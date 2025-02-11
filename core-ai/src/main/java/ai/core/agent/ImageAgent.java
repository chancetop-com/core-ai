package ai.core.agent;

import ai.core.image.ImageProvider;
import ai.core.image.providers.inner.GenerateImageRequest;
import ai.core.prompt.engines.MustachePromptTemplate;
import core.framework.crypto.Hash;
import core.framework.util.Maps;

import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class ImageAgent extends Node<ImageAgent> {

    public static Builder builder() {
        return new Builder();
    }

    String promptTemplate;
    ImageProvider imageProvider;

    @Override
    String execute(String query, Map<String, Object> variables) {
        setInput(query);

        var prompt = promptTemplate + query;
        Map<String, Object> context = variables == null ? Maps.newConcurrentHashMap() : new HashMap<>(variables);
        // compile and execute template
        prompt = new MustachePromptTemplate().execute(prompt, context, Hash.md5Hex(promptTemplate));

        var output = imageProvider.generateImage(new GenerateImageRequest(prompt)).url();

        setOutput(output);
        updateNodeStatus(NodeStatus.COMPLETED);
        return output;
    }

    public static class Builder extends Node.Builder<Builder, ImageAgent> {
        String prompt;
        ImageProvider imageProvider;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder imageProvider(ImageProvider imageProvider) {
            this.imageProvider = imageProvider;
            return this;
        }

        public ImageAgent build() {
            var agent = new ImageAgent();
            this.nodeType = NodeType.AGENT;
            build(agent);
            agent.promptTemplate = this.prompt;
            agent.imageProvider = this.imageProvider;
            return agent;
        }
    }
}
