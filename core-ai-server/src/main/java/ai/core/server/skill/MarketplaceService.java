package ai.core.server.skill;

import ai.core.server.domain.MarketplaceRepo;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import core.framework.inject.Inject;
import core.framework.web.exception.NotFoundException;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class MarketplaceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MarketplaceService.class);

    private static String deriveName(String repoUrl) {
        String path = repoUrl.replaceFirst("^https?://[^/]+/", "");
        path = path.replaceFirst("\\.git$", "");
        path = path.replaceFirst("/$", "");
        return path;
    }

    @Inject
    MarketplaceRepoStore repoStore;

    @Inject
    SkillStore skillStore;

    @Inject
    SkillService skillService;

    public boolean isInstalled(String repoId) {
        var repo = getRepo(repoId);
        String namespace = skillService.extractRepoOwner(repo.repoUrl);
        if (namespace == null) return false;
        return skillStore.countByNamespace(namespace) > 0;
    }

    public List<MarketplaceRepo> listRepos() {
        return repoStore.listAll();
    }

    public MarketplaceRepo getRepo(String id) {
        return repoStore.get(id)
            .orElseThrow(() -> new NotFoundException("marketplace repo not found, id=" + id));
    }

    public List<SkillDefinition> listRepoSkills(String repoId) {
        var repo = getRepo(repoId);
        String namespace = skillService.extractRepoOwner(repo.repoUrl);
        if (namespace == null) return List.of();
        return skillService.list(new SkillFilter(namespace, null), null, null, null, null, null);
    }

    public List<SkillDefinition> installRepo(String userId, String repoId) {
        var repo = getRepo(repoId);
        LOGGER.info("installing skills from marketplace repo, repoId={}, url={}", repoId, repo.repoUrl);
        return skillService.registerFromRepo(userId, repo.repoUrl, repo.branch, repo.skillPath);
    }

    public MarketplaceRepo register(String repoUrl, String branch) {
        String effectiveBranch = branch != null ? branch : "main";
        // derive name from repo URL: https://github.com/owner/repo → owner/repo
        String name = deriveName(repoUrl);

        // install skills first — this populates the skill collection and we get the skill count
        // Pass empty string so SkillService auto-detects plugin format if present
        LOGGER.info("registering marketplace repo, url={}, branch={}", repoUrl, effectiveBranch);
        var skills = skillService.registerFromRepo("marketplace", repoUrl, effectiveBranch, "");

        // Extract the auto-detected skill path from the first registered skill's repoConfig
        String detectedSkillPath = "";
        if (!skills.isEmpty() && skills.getFirst().repoConfig != null
            && skills.getFirst().repoConfig.skillPath != null
            && !skills.getFirst().repoConfig.skillPath.isBlank()) {
            detectedSkillPath = skills.getFirst().repoConfig.skillPath;
        }

        // create marketplace entry
        var repo = new MarketplaceRepo();
        repo.id = new ObjectId().toHexString();
        repo.name = name;
        repo.repoUrl = repoUrl;
        repo.branch = effectiveBranch;
        repo.skillPath = detectedSkillPath;
        repo.skillCount = skills.size();
        repo.featured = Boolean.FALSE;
        repo.createdAt = ZonedDateTime.now();
        repo.updatedAt = ZonedDateTime.now();
        repoStore.insert(repo);

        LOGGER.info("registered marketplace repo, id={}, name={}, skills={}, skillPath={}",
            repo.id, name, skills.size(), detectedSkillPath);
        return repo;
    }

    public void delete(String id) {
        repoStore.delete(id);
        LOGGER.info("deleted marketplace repo, id={}", id);
    }

    public long countUploadedSkills() {
        return skillStore.countBySourceType(SkillSourceType.UPLOAD);
    }
}
