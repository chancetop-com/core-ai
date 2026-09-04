package ai.core.server.skillhub;

import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * Raw binary endpoint serving a skill's ZIP archive — the exact byte sequence
 * {@code SkillArchiveBuilder} produces for sandbox materialization, tagged with the
 * skill digest headers so consumers can verify local copies. A separate route from the
 * JSON web service because archives are binary.
 *
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.SKILL_VIEW)
public class SkillArchiveController implements Controller {
    private static final ContentType APPLICATION_ZIP = ContentType.parse("application/zip");
    private static final String HEADER_SKILL_ID = "X-Skill-Id";
    private static final String HEADER_SKILL_DIGEST = "X-Skill-Digest";
    private static final String HEADER_SKILL_QUALIFIED_NAME = "X-Skill-Qualified-Name";

    @Inject
    SkillHubService hubService;

    @Override
    public Response execute(Request request) {
        var bundle = hubService.archive(request.pathParam("namespace"), request.pathParam("name"));
        var definition = bundle.definition();
        String safeNamespace = sanitize(definition.namespace);
        String safeName = sanitize(definition.name);
        return Response.bytes(bundle.bytes())
                .contentType(APPLICATION_ZIP)
                .header(HEADER_SKILL_ID, definition.id)
                .header(HEADER_SKILL_DIGEST, definition.digest == null ? "" : definition.digest)
                .header(HEADER_SKILL_QUALIFIED_NAME, definition.qualifiedName)
                .header("Content-Disposition", "attachment; filename=\"" + safeNamespace + "--" + safeName + ".zip\"");
    }

    private String sanitize(String value) {
        if (value == null) return "skill";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
