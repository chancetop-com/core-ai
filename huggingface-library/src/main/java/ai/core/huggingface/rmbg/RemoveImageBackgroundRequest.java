package ai.core.huggingface.rmbg;

import ai.core.huggingface.Path;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class RemoveImageBackgroundRequest {
    @Property(name = "path")
    public Path path;
}
