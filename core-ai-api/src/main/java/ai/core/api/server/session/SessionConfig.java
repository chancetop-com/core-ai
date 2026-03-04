package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class SessionConfig {
    @Property(name = "model")
    public String model;

    @Property(name = "temperature")
    public Double temperature;

    @Property(name = "systemPrompt")
    public String systemPrompt;

    @Property(name = "maxTurns")
    public Integer maxTurns;

    @Property(name = "autoApproveAll")
    public Boolean autoApproveAll;

    @Property(name = "workingDirectory")
    public String workingDirectory;

    @Property(name = "mcpServers")
    public List<String> mcpServers;
}
