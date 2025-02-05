package ai.core.lsp.service.nbls;

import ai.core.lsp.service.LanguageServerConfig;
import ai.core.lsp.service.LanguageServerService;

/**
 * @author stephen
 */
public class NBLanguageServerService extends LanguageServerService {
    public NBLanguageServerService(LanguageServerConfig config) {
        super(config, new NBLanguageServerManager());
    }
}
