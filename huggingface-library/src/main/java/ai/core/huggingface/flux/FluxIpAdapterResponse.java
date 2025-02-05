package ai.core.huggingface.flux;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class FluxIpAdapterResponse {
    @NotNull
    @Property(name = "url")
    public String url;
}
