package ai.core;

import ai.core.huggingface.HuggingfaceService;
import ai.core.huggingface.HuggingfaceWebService;
import core.framework.http.HTTPClient;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class HuggingFaceModule extends Module {
    @Override
    protected void initialize() {
        loadProperties("huggingface.properties");
        bindAPIClients();
        bindServices();
    }

    private void bindServices() {
        bind(HuggingfaceService.class);
    }

    private void bindAPIClients() {
        var client = HTTPClient.builder().timeout(Duration.ofMinutes(3)).trustAll().build();
        bind(new HuggingfaceWebService(client, requiredProperty("huggingface.token")));
    }
}
