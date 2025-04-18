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
    @NotBlank
    @Property(name = "model")
    public String model = "text-embedding-ada-002";

    @NotNull
    @Property(name = "input")
    public List<String> input;
}
