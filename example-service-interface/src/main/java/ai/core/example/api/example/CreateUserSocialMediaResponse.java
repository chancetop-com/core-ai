package ai.core.example.api.example;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateUserSocialMediaResponse {
    @Property(name = "content")
    public String content;

    @NotNull
    @Property(name = "contents")
    public List<String> contents = List.of();

    @NotNull
    @Property(name = "contents_cn")
    public List<String> contentsCn = List.of();

    @NotNull
    @Property(name = "image_suggestion")
    public String imageSuggestion;

    @NotNull
    @Property(name = "image_urls")
    public List<String> imageUrls = List.of();
}
