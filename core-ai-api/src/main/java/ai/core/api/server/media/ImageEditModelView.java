package ai.core.api.server.media;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ImageEditModelView {
    @Property(name = "modelId")
    public String modelId;

    @Property(name = "displayName")
    public String displayName;

    @Property(name = "providerName")
    public String providerName;

    @Property(name = "maskSupported")
    public Boolean maskSupported;

    /** null, or "annotation" when the model can only approximate a painted region. */
    @Property(name = "maskFallback")
    public String maskFallback;
}
