package ai.core.chat;

import ai.core.IntegrationTest;
import ai.core.api.tool.function.CoreAiParameter;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * author: lim chen
 * date: 2026/3/30
 * description:
 */
@Disabled
class LLMCallCaseTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(LLMCallCaseTest.class);
    @Inject
    LLMProviders llmProviders;

    @Test
    void simpleText() {
        var provider = llmProviders.getProvider();
        var request = CompletionRequest.of(List.of(Message.of(RoleType.SYSTEM, "you are a helpful assistant"), Message.of(RoleType.USER, "hello")), null, null, "gpt-4o", null);
        var result = provider.completion(request);
        LOGGER.info("result: {}", result.choices.getFirst().message.content);
    }

    @Test
    void structOutput() {
        var provider = llmProviders.getProvider();
        var result = provider.completionFormat("Respond in a friendly manner based on user comments.", "用户：张先生\\n评分：4星\\n评论内容：菜品味道不错，但等位时间太长了，希望能改进一下。", "gpt-4o", ReplyDTO.class);
        LOGGER.info("reply result: {}", JsonUtil.toJson(result));

    }

    static class ReplyDTO {
        @CoreAiParameter(name = "content", description = "reply content")
        public String content;
        @CoreAiParameter(name = "reason", description = "reply reason")
        public String reason;
    }


}
