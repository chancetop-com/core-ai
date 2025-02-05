package ai.core.example.site;

import ai.core.example.api.ExampleWebService;
import ai.core.example.site.api.ExampleAJAXWebService;
import ai.core.example.site.web.ExampleAJAXWebServiceImpl;
import core.framework.http.HTTPClient;
import core.framework.module.App;
import core.framework.module.SystemModule;

import java.time.Duration;

/**
 * @author stephen
 */
public class ExampleSiteApp extends App {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));
        loadProperties("app.properties");
        loadProperties("azure.properties");
        load(new FileModule());
        var client = HTTPClient.builder().timeout(Duration.ofMinutes(1)).trustAll().build();
        api().client(ExampleWebService.class, requiredProperty("example.service.url"), client);
        api().service(ExampleAJAXWebService.class, bind(ExampleAJAXWebServiceImpl.class));
    }
}
