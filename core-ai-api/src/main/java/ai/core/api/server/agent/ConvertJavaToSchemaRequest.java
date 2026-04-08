package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author Xander
 */
public class ConvertJavaToSchemaRequest {
    @NotNull
    @NotBlank
    @Property(name = "java_code")
    public String javaCode;
}
