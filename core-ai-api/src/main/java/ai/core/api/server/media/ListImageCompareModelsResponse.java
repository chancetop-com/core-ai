package ai.core.api.server.media;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListImageCompareModelsResponse {
    @Property(name = "models")
    public List<ImageCompareModelView> models;
}
