package ai.core.server;

import ai.core.api.server.DatasetWebService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.web.DatasetWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class DatasetModule extends Module {
    @Override
    protected void initialize() {
        bind(DatasetService.class);
        bind(DatasetRecordService.class);
        api().service(DatasetWebService.class, bind(DatasetWebServiceImpl.class));
    }
}
