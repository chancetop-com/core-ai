package ai.core.server.web;

import ai.core.api.server.trigger.CreateTriggerRequest;
import ai.core.api.server.trigger.ListTriggersRequest;
import ai.core.api.server.trigger.ListTriggersResponse;
import ai.core.api.server.trigger.TriggerView;
import ai.core.api.server.trigger.TriggerWebService;
import ai.core.api.server.trigger.UpdateTriggerRequest;
import ai.core.server.trigger.TriggerService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class TriggerWebServiceImpl implements TriggerWebService {
    @Inject
    WebContext webContext;

    @Inject
    TriggerService triggerService;

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public TriggerView create(CreateTriggerRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return triggerService.create(request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_VIEW)
    public ListTriggersResponse list(ListTriggersRequest request) {
        return triggerService.list(request != null ? request.type : null);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_VIEW)
    public TriggerView get(String id) {
        return triggerService.get(id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public TriggerView update(String id, UpdateTriggerRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return triggerService.update(id, request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public void delete(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        triggerService.delete(id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public TriggerView enable(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return triggerService.enable(id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public TriggerView disable(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return triggerService.disable(id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public TriggerView rotateSecret(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return triggerService.rotateSecret(id);
    }
}
