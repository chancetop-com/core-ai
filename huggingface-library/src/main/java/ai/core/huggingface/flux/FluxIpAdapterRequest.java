package ai.core.huggingface.flux;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class FluxIpAdapterRequest {
    @Property(name = "path")
    public String path;

    @Property(name = "prompt")
    public String prompt;

    @Property(name = "scale")
    public Double scale = 0.7;

    @Property(name = "seed")
    public Integer seed = 12345;

    @Property(name = "randomize_seed")
    public Boolean randomizeSeed = Boolean.TRUE;

    @Property(name = "width")
    public Integer imageWidth = 512;

    @Property(name = "height")
    public Integer imageHeight = 640;
}
