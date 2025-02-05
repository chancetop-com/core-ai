package ai.core.example.service;

import com.microsoft.azure.cognitiveservices.search.imagesearch.BingImageSearchAPI;
import com.microsoft.azure.cognitiveservices.search.imagesearch.models.MediaObject;
import core.framework.inject.Inject;

import java.util.List;

/**
 * @author stephen
 */
public class SearchImageService {
    @Inject
    BingImageSearchAPI bingImageSearchAPI;

    public List<String> search(String query, int size) {
        var apiRsp = bingImageSearchAPI.bingImages().search().withQuery(query).withCount(size).execute();
        return apiRsp == null ? List.of() : apiRsp.value().stream().map(MediaObject::contentUrl).toList();
    }
}
