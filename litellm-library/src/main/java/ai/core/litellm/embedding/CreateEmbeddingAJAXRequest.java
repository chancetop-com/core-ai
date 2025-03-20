package ai.core.litellm.embedding;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateEmbeddingAJAXRequest {
    @NotNull
    @Property(name = "model")
    public String model = "text-embedding-ada-002";

    @NotNull
    @NotBlank
    @Property(name = "input")
    public List<String> input;
}
