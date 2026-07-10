package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class MarketplaceListResponse {
    @Property(name = "repos")
    public List<MarketplaceRepoView> repos;

    @Property(name = "uploaded_count")
    public Long uploadedCount;
}
