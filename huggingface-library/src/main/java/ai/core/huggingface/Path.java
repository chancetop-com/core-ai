package ai.core.huggingface;

import core.framework.api.json.Property;
import core.framework.json.JSON;

/**
 * @author stephen
 */
public class Path {
    @Property(name = "path")
    public String path;

    @Override
    public String toString() {
        return JSON.toJSON(this);
    }
}
