package ai.core.example.site.api.socialmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateSocialMediaIdeasAJAXResponse {
    @NotNull
    @Property(name = "ideas")
    public List<String> ideas;
}
