package ai.core.litellm.image;

import ai.core.litellm.completion.UsageAJAXView;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class CreateImageAJAXResponse {
    @Property(name = "created")
    public Long createdTimestamp;

    @Property(name = "data")
    public List<GeneratedData> generatedDatas;

    @Property(name = "usage")
    public UsageAJAXView usage;

    public static class GeneratedData {
        @Property(name = "url")
        public String url;

        @Property(name = "revised_prompt")
        public String revisedPrompt;

        @Property(name = "content_filter_results")
        public FilterResult contentFilterResult;

        @Property(name = "prompt_filter_results")
        public FilterResult promptFilterResult;
    }

    public static class FilterResult {
        @Property(name = "hate")
        public ResultEntity hate;

        @Property(name = "profanity")
        public ResultEntity profanity;

        @Property(name = "self_harm")
        public ResultEntity selfHarm;

        @Property(name = "sexual")
        public ResultEntity sexual;

        @Property(name = "violence")
        public ResultEntity violence;
    }

    public static class ResultEntity {
        @Property(name = "filtered")
        public Boolean filtered;

        @Property(name = "severity")
        public String severity;
    }
}
