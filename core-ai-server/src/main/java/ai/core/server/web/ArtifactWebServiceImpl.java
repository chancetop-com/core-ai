package ai.core.server.web;

import ai.core.api.server.ArtifactWebService;
import ai.core.api.server.artifact.ListMyArtifactsRequest;
import ai.core.api.server.artifact.ListMyArtifactsResponse;
import ai.core.api.server.artifact.ListSharedArtifactsRequest;
import ai.core.api.server.artifact.ListSharedArtifactsResponse;
import ai.core.api.server.artifact.MyArtifactView;
import ai.core.api.server.artifact.SharedArtifactView;
import ai.core.server.artifact.ArtifactService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

import java.util.ArrayList;

public class ArtifactWebServiceImpl implements ArtifactWebService {
    @Inject
    WebContext webContext;

    @Inject
    ArtifactService artifactService;

    @Override
    public ListMyArtifactsResponse listMy(ListMyArtifactsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);

        var result = artifactService.listMy(userId, request.offset, request.limit);
        var response = new ListMyArtifactsResponse();
        response.total = result.total;
        response.artifacts = new ArrayList<>(result.artifacts.size());
        for (var item : result.artifacts) {
            var view = new MyArtifactView();
            view.id = item.id;
            view.fileName = item.fileName;
            view.contentType = item.contentType;
            view.size = item.size;
            view.createdAt = item.createdAt;
            view.sessionId = item.sessionId;
            view.sessionTitle = item.sessionTitle;
            response.artifacts.add(view);
        }
        return response;
    }

    @Override
    public ListSharedArtifactsResponse listShared(ListSharedArtifactsRequest request) {
        var result = artifactService.listShared(request.offset, request.limit, request.name, request.userId);
        var response = new ListSharedArtifactsResponse();
        response.total = result.total;
        response.artifacts = new ArrayList<>(result.artifacts.size());
        for (var item : result.artifacts) {
            var view = new SharedArtifactView();
            view.id = item.id;
            view.fileName = item.fileName;
            view.contentType = item.contentType;
            view.size = item.size;
            view.userId = item.userId;
            view.createdAt = item.createdAt;
            view.sharedAt = item.sharedAt;
            response.artifacts.add(view);
        }
        return response;
    }
}
