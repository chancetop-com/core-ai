package ai.core.lsp.service.jdtls;

import ai.core.lsp.service.LanguageServerConfig;
import ai.core.lsp.service.LanguageServerService;

/**
 * @author stephen
 * notice: jdtls can not work with core-ng project as expect yet!
 */
public class JDTLanguageServerService extends LanguageServerService {
    public JDTLanguageServerService(LanguageServerConfig config) {
        super(config, new JDTLanguageServerManager());
    }
}
