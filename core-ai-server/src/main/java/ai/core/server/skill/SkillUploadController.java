package ai.core.server.skill;

import ai.core.api.server.skill.SkillDefinitionView;
import ai.core.server.domain.User;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;

/**
 * @author stephen
 */
public class SkillUploadController implements Controller {
    @Inject
    SkillService skillService;

    @Inject
    MongoCollection<User> userCollection;

    @Inject
    WebContext webContext;

    @Override
    public Response execute(Request request) {
        var userId = AuthContext.userId(webContext);
        var user = userCollection.get(userId)
            .orElseThrow(() -> new BadRequestException("user not found"));
        var namespace = user.name != null ? user.name : userId.split("@")[0];

        var files = request.files();
        if (files.isEmpty()) {
            throw new BadRequestException("no file uploaded");
        }

        var skillFileEntry = files.get("skill_file");
        if (skillFileEntry == null) {
            skillFileEntry = files.entrySet().iterator().next().getValue();
        }

        byte[] skillFileBytes;
        try {
            skillFileBytes = Files.readAllBytes(skillFileEntry.path);
        } catch (IOException e) {
            throw new BadRequestException("failed to read uploaded file", "BAD_REQUEST", e);
        }

        var resources = new LinkedHashMap<String, byte[]>();
        for (var entry : files.entrySet()) {
            if ("skill_file".equals(entry.getKey())) continue;
            try {
                resources.put(entry.getKey(), Files.readAllBytes(entry.getValue().path));
            } catch (IOException e) {
                throw new BadRequestException("failed to read resource: " + entry.getKey(), "BAD_REQUEST", e);
            }
        }

        var entity = skillService.upload(userId, namespace, skillFileBytes, resources.isEmpty() ? null : resources);

        var view = new SkillDefinitionView();
        view.id = entity.id;
        view.namespace = entity.namespace;
        view.name = entity.name;
        view.qualifiedName = entity.qualifiedName;
        view.description = entity.description;
        view.sourceType = entity.sourceType.name();
        view.userId = entity.userId;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return Response.bean(view);
    }
}
