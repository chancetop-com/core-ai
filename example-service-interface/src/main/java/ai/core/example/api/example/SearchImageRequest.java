package ai.core.example.api.example;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SearchImageRequest {
    @NotNull
    @NotBlank
    @Property(name = "query")
    public String query;
}
