package ai.core.example.api.socialmedia;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class FaceIdImageRequest {
    @Property(name = "url")
    public String url;

    @Property(name = "prompt")
    public String prompt;
}
