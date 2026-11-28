package ai.core.server.web;

import ai.core.api.server.workspace.WorkspaceArchiveListView;
import ai.core.api.server.workspace.WorkspaceArchiveView;
import ai.core.api.server.workspace.WorkspaceCreateRequest;
import ai.core.api.server.workspace.WorkspaceEventListRequest;
import ai.core.api.server.workspace.WorkspaceEventListView;
import ai.core.api.server.workspace.WorkspaceEventView;
import ai.core.api.server.workspace.WorkspaceListRequest;
import ai.core.api.server.workspace.WorkspaceListView;
import ai.core.api.server.workspace.WorkspaceResetRequest;
import ai.core.api.server.workspace.WorkspaceRestoreRequest;
import ai.core.api.server.workspace.WorkspaceRetryRequest;
import ai.core.api.server.workspace.WorkspaceUpdateRequest;
import ai.core.api.server.workspace.WorkspaceUsageView;
import ai.core.api.server.workspace.WorkspaceView;
import ai.core.api.server.workspace.WorkspaceWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.workspace.WorkspaceArchiveRecord;
import ai.core.server.workspace.WorkspaceArchiveService;
import ai.core.server.workspace.WorkspaceArchiveSummary;
import ai.core.server.workspace.WorkspaceBootstrapRunner;
import ai.core.server.workspace.WorkspaceCreateSpec;
import ai.core.server.workspace.WorkspaceEventRecord;
import ai.core.server.workspace.WorkspaceException;
import ai.core.server.workspace.WorkspaceHttpErrors;
import ai.core.server.workspace.WorkspaceRecord;
import ai.core.server.workspace.WorkspaceService;
import ai.core.server.workspace.WorkspaceUpdateSpec;
import ai.core.server.workspace.WorkspaceUsageRecord;
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
    @Inject
    WorkspaceArchiveService archiveService;

    @Override
    public WorkspaceListView list(WorkspaceListRequest request) {
        var includeDeleted = request != null && Boolean.TRUE.equals(request.includeDeleted);
        var view = new WorkspaceListView();
        view.items = call(() -> {
            var records = workspaceService.list(userId(), includeDeleted);
            var ids = records.stream().map(r -> r.id).toList();
            var usageByWorkspace = workspaceService.usageByWorkspaces(ids);
            var archivesByWorkspace = archiveService.summarizeReady(ids);
            return records.stream().map(r -> view(r, usageByWorkspace.get(r.id), archivesByWorkspace.get(r.id))).toList();
        });
        return view;
    }

    @Override
    public WorkspaceView create(WorkspaceCreateRequest request) {
        var spec = new WorkspaceCreateSpec(request.name, request.agentId, request.sizeGb, request.profile,
                request.idleSleepMinutes, request.bootstrapScript, request.startScript);
        return call(() -> view(workspaceService.create(userId(), spec)));
    }

    @Override
    public WorkspaceView get(String id) {
        return call(() -> {
            var userId = userId();
            var record = workspaceService.get(userId, id);
            var usage = workspaceService.usage(userId, id).orElse(null);
            var summary = archiveService.summary(id);
            return view(record, usage, summary);
        });
    }

    @Override
    public WorkspaceView update(String id, WorkspaceUpdateRequest request) {
        var spec = new WorkspaceUpdateSpec(request.name, request.idleSleepMinutes,
                request.bootstrapScript, request.startScript, request.image, request.template);
        return call(() -> {
            var updated = workspaceService.update(userId(), id, spec);
            var summary = archiveService.summary(updated.id);
            return view(updated, null, summary);
        });
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
    public WorkspaceView retry(String id, WorkspaceRetryRequest request) {
        var rerunBootstrap = request != null && Boolean.TRUE.equals(request.rerunBootstrap);
        return call(() -> view(workspaceService.retryAsync(userId(), id, rerunBootstrap)));
    }

    @Override
    public WorkspaceEventListView events(String id, WorkspaceEventListRequest request) {
        var limit = request != null && request.limit != null ? request.limit : 20;
        var view = new WorkspaceEventListView();
        view.items = call(() -> workspaceService.events(userId(), id, limit).stream().map(this::eventView).toList());
        return view;
    }

    @Override
    public WorkspaceView undelete(String id) {
        return call(() -> view(workspaceService.restore(userId(), id)));
    }

    @Override
    public WorkspaceUsageView usage(String id) {
        return call(() -> workspaceService.usage(userId(), id)
                .map(this::usageView)
                .orElseThrow(() -> new WorkspaceException(WorkspaceException.NOT_FOUND, "workspace usage not found: " + id)));
    }

    @Override
    public WorkspaceArchiveView createArchive(String id) {
        return call(() -> archiveView(archiveService.create(userId(), id)));
    }

    @Override
    public WorkspaceArchiveListView listArchives(String id) {
        var view = new WorkspaceArchiveListView();
        view.items = call(() -> archiveService.list(userId(), id).stream().map(this::archiveView).toList());
        return view;
    }

    @Override
    public WorkspaceView restore(String id, WorkspaceRestoreRequest request) {
        var allowOverwrite = request != null && Boolean.TRUE.equals(request.allowOverwrite);
        return call(() -> {
            var restored = archiveService.restore(userId(), id, request.archiveId, request.newName, allowOverwrite);
            var summary = archiveService.summary(restored.id);
            return view(restored, null, summary);
        });
    }

    @Override
    public void deleteArchive(String id, String archiveId) {
        call(() -> {
            archiveService.delete(userId(), id, archiveId);
            return null;
        });
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

    private WorkspaceArchiveView archiveView(WorkspaceArchiveRecord record) {
        var view = new WorkspaceArchiveView();
        view.id = record.id;
        view.workspaceId = record.workspaceId;
        view.volumeGeneration = record.volumeGeneration;
        view.sha256 = record.sha256;
        view.sizeBytes = record.sizeBytes;
        view.fileCount = record.fileCount;
        view.image = record.image;
        view.status = record.status.name();
        view.failureReason = record.failureReason;
        view.createdBy = record.createdBy;
        view.createdAt = record.createdAt;
        view.updatedAt = record.updatedAt;
        return view;
    }

    private WorkspaceEventView eventView(WorkspaceEventRecord record) {
        var view = new WorkspaceEventView();
        view.id = record.id;
        view.eventType = record.eventType;
        view.actor = record.actor;
        view.payload = record.payloadJson;
        view.createdAt = record.createdAt;
        return view;
    }

    private WorkspaceUsageView usageView(WorkspaceUsageRecord record) {
        var view = new WorkspaceUsageView();
        view.usedBytes = record.usedBytes;
        view.capacityBytes = record.capacityBytes;
        view.availableBytes = record.availableBytes;
        view.inodesUsed = record.inodesUsed;
        view.inodesTotal = record.inodesTotal;
        view.sampledAt = record.sampledAt;
        return view;
    }

    private WorkspaceView view(WorkspaceRecord record) {
        return view(record, null, null);
    }

    private WorkspaceView view(WorkspaceRecord record, WorkspaceUsageRecord usage, WorkspaceArchiveSummary archiveSummary) {
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
        if (usage != null) {
            view.usedBytes = usage.usedBytes;
            view.capacityBytes = usage.capacityBytes;
        }
        view.storageClass = record.storageClass;
        view.profile = record.profile != null ? record.profile.id : null;
        view.mountPath = record.mountPath;
        view.image = record.image;
        view.networkEnabled = record.networkEnabled;
        view.networkProfile = record.networkProfile;
        view.idleSleepMinutes = record.idleSleepMinutes;
        view.lastUsedAt = record.lastUsedAt;
        view.lastProvisionedAt = record.lastProvisionedAt;
        view.lastWokenAt = record.lastWokenAt;
        view.lastSleptAt = record.lastSleptAt;
        view.bootstrapCompletedAt = record.bootstrapCompletedAt;
        view.bootstrapScriptHash = record.bootstrapScriptHash;
        view.startScriptHash = record.startScriptHash;
        view.bootstrapScriptChanged = WorkspaceBootstrapRunner.scriptChanged(record.bootstrapScript, record.bootstrapScriptHash);
        view.lastBootstrapLog = record.lastBootstrapLog;
        view.envFilePresent = record.envFilePresent;
        view.bootstrapScript = record.bootstrapScript;
        view.startScript = record.startScript;
        view.errorCode = record.errorCode;
        view.errorMessage = record.errorMessage;
        view.deletedAt = record.deletedAt;
        applyArchiveMeta(view, record, archiveSummary);
        view.createdAt = record.createdAt;
        view.updatedAt = record.updatedAt;
        return view;
    }

    private void applyArchiveMeta(WorkspaceView view, WorkspaceRecord record, WorkspaceArchiveSummary archiveSummary) {
        var lost = archiveService.isVolumeLostOrUnavailable(record);
        if (lost) {
            view.volumeStatus = record.volumeStatus.name();
            view.errorCode = record.errorCode;
            view.errorMessage = record.errorMessage;
        }
        var archiveCount = archiveSummary != null ? archiveSummary.archiveCount() : 0L;
        view.archiveCount = archiveCount;
        view.latestArchiveAt = archiveSummary != null ? archiveSummary.latestArchiveAt() : null;
        view.recoverable = archiveCount > 0 && lost;
    }
}
