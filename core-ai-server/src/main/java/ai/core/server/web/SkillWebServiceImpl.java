package ai.core.server.web;

import ai.core.api.server.SkillWebService;
import ai.core.api.server.skill.ListSkillsRequest;
import ai.core.api.server.skill.ListSkillsResponse;
import ai.core.api.server.skill.RegisterRepoSkillRequest;
import ai.core.api.server.skill.RegisterRepoSkillsResponse;
import ai.core.api.server.skill.SkillDefinitionView;
import ai.core.api.server.skill.SkillDownloadResponse;
import ai.core.api.server.skill.UpdateSkillRequest;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.skill.SkillService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class SkillWebServiceImpl implements SkillWebService {
    @Inject
    SkillService skillService;

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
        var skills = skillService.list(request.namespace, request.sourceType, request.query);
        var response = new ListSkillsResponse();
        response.skills = skills.stream().map(this::toView).toList();
        response.total = (long) response.skills.size();
        return response;
    }

    @Override
    public SkillDefinitionView get(String id) {
        return toView(skillService.get(id));
    }

    @Override
    public SkillDefinitionView update(String id, UpdateSkillRequest request) {
        return toView(skillService.update(id, request.description));
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
