package ai.core.server.web;

import ai.core.api.server.CapabilitiesWebService;
import ai.core.api.server.ServerCapabilities;

/**
 * @author stephen
 */
public class CapabilitiesWebServiceImpl implements CapabilitiesWebService {
    public boolean authDisabled;
    public boolean sandboxTerminalEnabled;
    public String sandboxTerminalGatewayUrl = "";

    @Override
    public ServerCapabilities get() {
        var caps = new ServerCapabilities();
        caps.chat = Boolean.TRUE;
        caps.traces = Boolean.TRUE;
        caps.prompts = Boolean.TRUE;
        caps.dashboard = Boolean.TRUE;
        caps.authRequired = authDisabled ? Boolean.FALSE : Boolean.TRUE;
        caps.sandboxTerminalEnabled = sandboxTerminalEnabled ? Boolean.TRUE : Boolean.FALSE;
        caps.sandboxTerminalGatewayUrl = sandboxTerminalGatewayUrl;
        return caps;
    }
}
