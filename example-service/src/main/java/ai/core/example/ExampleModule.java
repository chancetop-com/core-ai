package ai.core.example;

import ai.core.example.api.ExampleWebService;
import ai.core.example.api.ExampleWebServiceImpl;
import ai.core.example.service.ExampleService;
import ai.core.example.service.UserInfoService;
import ai.core.example.service.WeatherService;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ExampleModule extends Module {
    @Override
    protected void initialize() {
        bind(WeatherService.class);
        bind(UserInfoService.class);
        bind(ExampleService.class);
        api().service(ExampleWebService.class, bind(ExampleWebServiceImpl.class));
    }
}
