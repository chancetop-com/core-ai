package ai.core.server.web;

import ai.core.api.server.workspace.WorkspaceCreateRequest;
import ai.core.api.server.workspace.WorkspaceListView;
import ai.core.api.server.workspace.WorkspaceResetRequest;
import ai.core.api.server.workspace.WorkspaceUpdateRequest;
import ai.core.api.server.workspace.WorkspaceView;
import ai.core.api.server.workspace.WorkspaceWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.workspace.WorkspaceException;
import ai.core.server.workspace.WorkspaceHttpErrors;
import ai.core.server.workspace.WorkspaceRecord;
import ai.core.server.workspace.WorkspaceService;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

import java.util.function.Supplier;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class WorkspaceWebServiceImpl implements WorkspaceWebService {
    @Inject
    WebContext webContext;
    @Inject
    WorkspaceService workspaceService;

    @Override
    public WorkspaceListView list() {
        var view = new WorkspaceListView();
        view.items = call(() -> workspaceService.list(userId()).stream().map(this::view).toList());
        return view;
    }

    @Override
    public WorkspaceView create(WorkspaceCreateRequest request) {
        return call(() -> view(workspaceService.create(userId(), request.name, request.agentId,
                request.sizeGb, request.profile)));
    }

    @Override
    public WorkspaceView get(String id) {
        return call(() -> view(workspaceService.get(userId(), id)));
    }

    @Override
    public WorkspaceView update(String id, WorkspaceUpdateRequest request) {
        return call(() -> view(workspaceService.update(userId(), id, request.name, request.idleSleepMinutes)));
    }

    @Override
    public void delete(String id) {
        call(() -> {
            workspaceService.delete(userId(), id);
            return null;
        });
    }

    @Override
    public WorkspaceView wake(String id) {
        return call(() -> view(workspaceService.wakeAsync(userId(), id)));
    }

    @Override
    public WorkspaceView sleep(String id) {
        return call(() -> {
            workspaceService.sleep(userId(), id);
            return view(workspaceService.get(userId(), id));
        });
    }

    @Override
    public WorkspaceView reset(String id, WorkspaceResetRequest request) {
        return call(() -> view(workspaceService.reset(userId(), id, request.confirmName)));
    }

    @Override
    public WorkspaceView undelete(String id) {
        return call(() -> view(workspaceService.restore(userId(), id)));
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }

    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (WorkspaceException e) {
            throw WorkspaceHttpErrors.translate(e);
        }
    }

    private WorkspaceView view(WorkspaceRecord record) {
        var view = new WorkspaceView();
        view.id = record.id;
        view.name = record.name;
        view.agentId = record.agentId;
        view.isDefault = record.isDefault;
        view.volumeStatus = record.volumeStatus.name();
        view.runtimeStatus = record.runtimeStatus.name();
        view.activeVolumeGeneration = record.activeVolumeGeneration;
        view.runtimeGeneration = record.runtimeGeneration;
        view.sizeGb = record.sizeGb;
        view.storageClass = record.storageClass;
        view.profile = record.profile != null ? record.profile.id : null;
        view.mountPath = record.mountPath;
        view.networkEnabled = record.networkEnabled;
        view.networkProfile = record.networkProfile;
        view.idleSleepMinutes = record.idleSleepMinutes;
        view.lastUsedAt = record.lastUsedAt;
        view.lastProvisionedAt = record.lastProvisionedAt;
        view.errorCode = record.errorCode;
        view.errorMessage = record.errorMessage;
        view.deletedAt = record.deletedAt;
        view.createdAt = record.createdAt;
        view.updatedAt = record.updatedAt;
        return view;
    }
}
