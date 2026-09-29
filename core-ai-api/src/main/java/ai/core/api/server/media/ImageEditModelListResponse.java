package ai.core.api.server.media;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ImageEditModelListResponse {
    @Property(name = "defaultModelId")
    public String defaultModelId;

    @Property(name = "models")
    public List<ImageEditModelView> models;
}
