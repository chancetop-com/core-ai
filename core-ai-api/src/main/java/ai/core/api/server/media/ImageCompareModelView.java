package ai.core.api.server.media;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ImageCompareModelView {
    @Property(name = "modelId")
    public String modelId;

    @Property(name = "upstreamModel")
    public String upstreamModel;

    @Property(name = "providerName")
    public String providerName;

    @Property(name = "imagePricePerImage")
    public Double imagePricePerImage;

    @Property(name = "hint")
    public String hint;
}
