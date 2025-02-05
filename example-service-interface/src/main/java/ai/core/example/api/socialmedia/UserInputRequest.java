package ai.core.example.api.socialmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;

/**
 * @author stephen
 */
public class UserInputRequest {
    @NotBlank
    @Property(name = "id")
    public String id;

    @NotBlank
    @Property(name = "query")
    public String query;
}
