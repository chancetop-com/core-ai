package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class RegisterRepoSkillRequest {
    @NotNull
    @NotBlank
    @Property(name = "repo_url")
    public String repoUrl;

    @Property(name = "branch")
    public String branch;

    @Property(name = "skill_path")
    public String skillPath;
}
