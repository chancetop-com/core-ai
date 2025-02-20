package ai.core.example.api.example;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class IpAdapterImageRequest {
    @Property(name = "url")
    public String url;

    @Property(name = "prompt")
    public String prompt;

    @Property(name = "width")
    public Integer imageWidth;

    @Property(name = "height")
    public Integer imageHeight;
}
