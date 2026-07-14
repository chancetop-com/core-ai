package ai.core.server.web;

import ai.core.api.server.SkillWebService;
import ai.core.api.server.skill.ListSkillsRequest;
import ai.core.api.server.skill.ListSkillsResponse;
import ai.core.api.server.skill.MarketplaceListResponse;
import ai.core.api.server.skill.MarketplaceRepoDetailResponse;
import ai.core.api.server.skill.CreateMarketplaceRepoRequest;
import ai.core.api.server.skill.MarketplaceRepoView;
import ai.core.api.server.skill.RegisterRepoSkillRequest;
import ai.core.api.server.skill.RegisterRepoSkillsResponse;
import ai.core.api.server.skill.SkillDefinitionView;
import ai.core.api.server.skill.SkillDownloadResponse;
import ai.core.api.server.skill.UpdateSkillRequest;
import ai.core.server.domain.MarketplaceRepo;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.server.skill.MarketplaceService;
import ai.core.server.skill.SkillFilter;
import ai.core.server.skill.SkillService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

import java.util.List;

/**
 * @author stephen
 */
public class SkillWebServiceImpl implements SkillWebService {
    @Inject
    SkillService skillService;

    @Inject
    MarketplaceService marketplaceService;

    @Inject
    WebContext webContext;

    @Override
    public RegisterRepoSkillsResponse registerFromRepo(RegisterRepoSkillRequest request) {
        var userId = AuthContext.userId(webContext);
        var skills = skillService.registerFromRepo(userId, request.repoUrl, request.branch, request.skillPath);
        var response = new RegisterRepoSkillsResponse();
        response.skills = skills.stream().map(this::toView).toList();
        return response;
    }

    @Override
    public ListSkillsResponse list(ListSkillsRequest request) {
        var effectiveRequest = request != null ? request : new ListSkillsRequest();
        var filter = new SkillFilter(effectiveRequest.namespace, effectiveRequest.sourceType);
        var skills = skillService.list(
            filter,
            effectiveRequest.userId,
            effectiveRequest.query,
            effectiveRequest.searchIn,
            effectiveRequest.offset,
            effectiveRequest.limit
        );
        var response = new ListSkillsResponse();
        response.skills = skills.stream().map(this::toView).toList();
        response.total = skillService.count(
            filter,
            effectiveRequest.userId,
            effectiveRequest.query,
            effectiveRequest.searchIn
        );
        return response;
    }

    @Override
    public SkillDefinitionView get(String id) {
        return toView(skillService.get(id));
    }

    @Override
    public SkillDefinitionView update(String id, UpdateSkillRequest request) {
        List<SkillResource> resources = null;
        if (request.resources != null) {
            resources = request.resources.stream().map(r -> {
                var sr = new SkillResource();
                sr.path = r.path;
                sr.content = r.content;
                return sr;
            }).toList();
        }
        return toView(skillService.update(id, request.description, request.content, request.allowedTools, resources));
    }

    @Override
    public void delete(String id) {
        skillService.delete(id);
    }

    @Override
    public SkillDefinitionView syncFromRepo(String id) {
        return toView(skillService.syncFromRepo(id));
    }

    @Override
    public SkillDownloadResponse download(String id) {
        var entity = skillService.download(id);
        var response = new SkillDownloadResponse();
        response.name = entity.name;
        response.namespace = entity.namespace;
        response.content = entity.content;
        if (entity.resources != null) {
            response.resources = entity.resources.stream().map(r -> {
                var rv = new SkillDownloadResponse.SkillResourceView();
                rv.path = r.path;
                rv.content = r.content;
                return rv;
            }).toList();
        }
        return response;
    }

    @Override
    public MarketplaceRepoView createMarketplaceRepo(CreateMarketplaceRepoRequest request) {
        var repo = marketplaceService.register(request.repoUrl, request.branch);
        var view = toRepoView(repo);
        view.installed = true;
        return view;
    }

    @Override
    public MarketplaceListResponse marketplace() {
        var repos = marketplaceService.listRepos();
        long uploadedCount = marketplaceService.countUploadedSkills();
        var response = new MarketplaceListResponse();
        response.repos = repos.stream().map(repo -> {
            var view = toRepoView(repo);
            view.installed = marketplaceService.isInstalled(repo.id);
            return view;
        }).toList();
        response.uploadedCount = uploadedCount;
        return response;
    }

    @Override
    public MarketplaceRepoDetailResponse marketplaceRepo(String repoId) {
        var repo = marketplaceService.getRepo(repoId);
        var skills = marketplaceService.listRepoSkills(repoId);
        var response = new MarketplaceRepoDetailResponse();
        response.id = repo.id;
        response.name = repo.name;
        response.repoUrl = repo.repoUrl;
        response.description = repo.description;
        response.iconUrl = repo.iconUrl;
        response.branch = repo.branch;
        response.skillPath = repo.skillPath;
        response.skillCount = repo.skillCount;
        response.category = repo.category;
        response.installed = !skills.isEmpty();
        response.createdAt = repo.createdAt;
        response.skills = skills.stream().map(this::toView).toList();
        return response;
    }

    @Override
    public RegisterRepoSkillsResponse installMarketplaceRepo(String repoId) {
        var userId = AuthContext.userId(webContext);
        var skills = marketplaceService.installRepo(userId, repoId);
        var response = new RegisterRepoSkillsResponse();
        response.skills = skills.stream().map(this::toView).toList();
        return response;
    }

    @Override
    public void deleteMarketplaceRepo(String repoId) {
        marketplaceService.delete(repoId);
    }

    private MarketplaceRepoView toRepoView(MarketplaceRepo repo) {
        var view = new MarketplaceRepoView();
        view.id = repo.id;
        view.name = repo.name;
        view.repoUrl = repo.repoUrl;
        view.description = repo.description;
        view.iconUrl = repo.iconUrl;
        view.skillCount = repo.skillCount;
        view.featured = repo.featured;
        view.category = repo.category;
        view.createdAt = repo.createdAt;
        return view;
    }

    private SkillDefinitionView toView(SkillDefinition entity) {
        var view = new SkillDefinitionView();
        view.id = entity.id;
        view.namespace = entity.namespace;
        view.name = entity.name;
        view.qualifiedName = entity.qualifiedName;
        view.description = entity.description;
        view.sourceType = entity.sourceType.name();
        view.allowedTools = entity.allowedTools;
        view.metadata = entity.metadata;
        view.version = entity.version;
        view.userId = entity.userId;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }
}
