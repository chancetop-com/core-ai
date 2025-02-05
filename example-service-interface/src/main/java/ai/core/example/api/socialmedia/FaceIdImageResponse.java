package ai.core.example.api.socialmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class FaceIdImageResponse {
    @NotNull
    @Property(name = "urls")
    public List<String> urls;
}
