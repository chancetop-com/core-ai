package ai.core.lsp;

import ai.core.lsp.service.jdtls.JDTLanguageServerService;
import ai.core.lsp.service.nbls.NBLanguageServerService;
import ai.core.lsp.service.LanguageServerConfig;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class LanguageServerModule extends Module {
    @Override
    protected void initialize() {
        loadProperties("lsp.properties");
        property("ls.jdt").ifPresent(v -> {
            if (Boolean.parseBoolean(v)) {
                bind(new JDTLanguageServerService(new LanguageServerConfig(requiredProperty("ls.jdt.home"), null, null)));
            }
        });
        property("ls.nb").ifPresent(v -> {
            if (Boolean.parseBoolean(v)) {
                bind(new NBLanguageServerService(new LanguageServerConfig(requiredProperty("ls.jdt.home"), requiredProperty("ls.jdk.home"), null)));
            }
        });
    }
}
