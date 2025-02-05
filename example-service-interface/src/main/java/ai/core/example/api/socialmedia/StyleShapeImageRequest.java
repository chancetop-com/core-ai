package ai.core.example.api.socialmedia;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class StyleShapeImageRequest {
    @Property(name = "url")
    public String url;

    @Property(name = "style")
    public String style;

    @Property(name = "prompt")
    public String prompt;
}
