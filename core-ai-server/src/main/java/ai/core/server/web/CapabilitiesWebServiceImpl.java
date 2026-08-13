package ai.core.server.web;

import ai.core.api.a2a.A2ACapabilities;
import ai.core.api.server.CapabilitiesWebService;

/**
 * @author stephen
 */
public class CapabilitiesWebServiceImpl implements CapabilitiesWebService {
    public boolean authDisabled;

    @Override
    public A2ACapabilities get() {
        var caps = A2ACapabilities.serverMode();
        if (authDisabled) {
            caps.authRequired = Boolean.FALSE;
        }
        return caps;
    }
}
