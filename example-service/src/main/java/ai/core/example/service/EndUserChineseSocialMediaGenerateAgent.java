package ai.core.example.service;

import ai.core.agent.Agent;
import ai.core.agent.formatter.formatters.DefaultJsonFormatter;
import ai.core.llm.LLMProvider;

/**
 * @author stephen
 */
public class EndUserChineseSocialMediaGenerateAgent {
    public static Agent of(LLMProvider llmProvider) {
        return Agent.builder()
            .name("end-user-social-media-agent")
            .description("generate social media post for end users")
            .temperature(1.2D)
            .systemPrompt("")
            .promptTemplate("""
                我是一个20岁的女孩，我刚在网红奶茶店打卡喝了一杯奶茶，请帮我写5个小红书风格的文案让我参考分享，并生成一个推荐的配图的描述给我，下面一些的要求：
                1. 内容要求:
                    - 文案要与我的灵感关联，并与我的图片的描述有一点关联（非必须）.
                    - 每个文案长度最好不一，其中3个为30字左右的，其中2个为80字左右的，有各自的风格.
                    - 文案内容风格活泼可爱，也可以多样化，并多加一些表情和流行元素.
                2. 图片推荐的要求:
                    - 把你的图片描述输出到image_suggestion中，并写出适合我去搜索图片的3个关键词image_suggestion_keywords.
                    - 关键词为2-5个字的词，用逗号（半角字符的逗号）分隔，按关联度排序.
                3. 输出格式要求:
                    - 输出为json格式.
                    - 文案内容输出到post_contents_cn.
                    - 完整的输出应该包含3个字段: post_contents_cn, image_suggestion, image_suggestion_keywords.
                下面是一个输出示例：
                {"post_contents_cn": ["post1", "post2", "post3", "post4", "post5"], "image_suggestion": "suggestion", "image_suggestion_keywords": "keyword1, keyword2, keyword3"}
                用户当前的位置: {{location}}
                用户的灵感:
            """)
            .formatter(new DefaultJsonFormatter())
            .llmProvider(llmProvider)
            .build();
    }
}
