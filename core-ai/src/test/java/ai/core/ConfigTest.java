package ai.core;

import org.junit.jupiter.api.Test;

import static core.framework.test.Assertions.assertConfDirectory;


/**
 * @author Albert
 */
class ConfigTest extends IntegrationTest {
    @Test
    void conf() {
        assertConfDirectory().overridesDefaultResources();
    }
}
