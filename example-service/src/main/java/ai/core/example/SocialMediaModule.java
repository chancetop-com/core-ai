package ai.core.example;

import ai.core.example.socialmedia.service.SearchImageService;
import ai.core.example.socialmedia.service.SocialMediaService;
import com.microsoft.azure.cognitiveservices.search.imagesearch.BingImageSearchAPI;
import com.microsoft.azure.cognitiveservices.search.imagesearch.BingImageSearchManager;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SocialMediaModule extends Module {
    @Override
    protected void initialize() {
        initBingSearch();
        bind(SearchImageService.class);
        bind(SocialMediaService.class);
    }

    private void initBingSearch() {
        loadProperties("azure.properties");
        bind(BingImageSearchAPI.class, BingImageSearchManager.authenticate(requiredProperty("azure.endpoint"), requiredProperty("azure.bing.search.key")));
    }
}
