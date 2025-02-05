package ai.core.agent;

import ai.core.llm.LLMProvider;
import ai.core.llm.providers.inner.CaptionImageRequest;

import java.util.Map;

/**
 * @author stephen
 */
public class VisionAgent extends Node<VisionAgent> {

    public static Builder builder() {
        return new Builder();
    }

    String prompt;
    LLMProvider llmProvider;

    @Override
    String execute(String query, Map<String, Object> variables) {
        setInput(query);

        var output = llmProvider.captionImage(new CaptionImageRequest(prompt, query)).caption();

        setOutput(output);
        updateNodeStatus(NodeStatus.COMPLETED);
        return output;
    }

    public static class Builder extends Node.Builder<Builder, VisionAgent> {
        String prompt;
        LLMProvider llmProvider;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder llmProvider(LLMProvider llmProvider) {
            this.llmProvider = llmProvider;
            return this;
        }

        public VisionAgent build() {
            var agent = new VisionAgent();
            this.nodeType = NodeType.AGENT;
            build(agent);
            agent.prompt = this.prompt;
            agent.llmProvider = this.llmProvider;
            return agent;
        }
    }
}
