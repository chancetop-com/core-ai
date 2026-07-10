package ai.core.api.server.skill;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CreateMarketplaceRepoRequest {
    @Property(name = "repo_url")
    public String repoUrl;

    @Property(name = "branch")
    public String branch;
}
