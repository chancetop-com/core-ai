package ai.core.example;

import ai.core.HuggingFaceModule;
import ai.core.MultiAgentModule;
import ai.core.example.api.ExampleWebService;
import ai.core.example.api.ExampleWebServiceImpl;
import ai.core.example.service.ChatAgent;
import ai.core.example.service.ExampleService;
import ai.core.example.service.SearchImageService;
import ai.core.example.service.UserInfoService;
import ai.core.example.service.WeatherService;
import com.microsoft.azure.cognitiveservices.search.imagesearch.BingImageSearchAPI;
import com.microsoft.azure.cognitiveservices.search.imagesearch.BingImageSearchManager;
import core.framework.module.App;
import core.framework.module.SystemModule;

/**
 * @author stephen
 */
public class ExampleApp extends App {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));
        load(new MultiAgentModule());
        load(new HuggingFaceModule());
        initBingSearch();
        bind(SearchImageService.class);
        bind(WeatherService.class);
        bind(UserInfoService.class);
        bind(ChatAgent.class);
        bind(ExampleService.class);
        api().service(ExampleWebService.class, bind(ExampleWebServiceImpl.class));
    }

    private void initBingSearch() {
        loadProperties("azure.properties");
        bind(BingImageSearchAPI.class, BingImageSearchManager.authenticate(requiredProperty("azure.endpoint"), requiredProperty("azure.bing.search.key")));
    }
}
