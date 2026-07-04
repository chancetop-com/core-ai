package ai.core.api.server.settings;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SystemSettingsRequest {
    @Property(name = "memory_extraction_model")
    public String memoryExtractionModel;
}
