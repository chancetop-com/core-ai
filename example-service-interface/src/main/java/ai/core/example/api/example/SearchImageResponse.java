package ai.core.example.api.example;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class SearchImageResponse {
    @NotNull
    @Property(name = "urls")
    public List<String> urls;
}
