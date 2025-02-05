package ai.core.litellm.embedding;

import ai.core.litellm.completion.UsageAJAXView;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateEmbeddingAJAXResponse {
    @NotNull
    @Property(name = "data")
    public List<Data> data;

    @NotNull
    @Property(name = "model")
    public String model;

    @NotNull
    @Property(name = "usage")
    public UsageAJAXView usage;

    public static class Data {
        @Property(name = "index")
        public Integer index;

        @NotNull
        @Property(name = "embedding")
        public List<Double> embedding;
    }
}
