package ai.core.example;

import ai.core.example.naixt.api.NaixtWebServiceImpl;
import ai.core.example.naixt.service.LanguageServerToolingService;
import ai.core.example.naixt.service.NaixtService;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class NaixtModule extends Module {
    @Override
    protected void initialize() {
        bind(LanguageServerToolingService.class);
        bind(NaixtService.class);
        api().service(NaixtWebServiceImpl.class, bind(NaixtWebServiceImpl.class));
    }
}
