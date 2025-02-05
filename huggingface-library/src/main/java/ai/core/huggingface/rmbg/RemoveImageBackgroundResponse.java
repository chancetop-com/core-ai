package ai.core.huggingface.rmbg;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class RemoveImageBackgroundResponse {
    @NotNull
    @Property(name = "url")
    public String url;
}
