package ai.core.server.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilitiesWebServiceImplTest {
    @Test
    void exposesTerminalFlagWhenEnabled() {
        var impl = new CapabilitiesWebServiceImpl();
        impl.sandboxTerminalEnabled = true;
        assertEquals(Boolean.TRUE, impl.get().sandboxTerminalEnabled);
        assertEquals(Boolean.TRUE, impl.get().chat);
    }

    @Test
    void terminalFlagDefaultsToFalse() {
        var impl = new CapabilitiesWebServiceImpl();
        assertEquals(Boolean.FALSE, impl.get().sandboxTerminalEnabled);
    }

    @Test
    void authOverrideStillWorks() {
        var impl = new CapabilitiesWebServiceImpl();
        impl.authDisabled = true;
        assertEquals(Boolean.FALSE, impl.get().authRequired);
    }
}
