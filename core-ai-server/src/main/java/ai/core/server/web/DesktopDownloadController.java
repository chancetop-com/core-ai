package ai.core.server.web;

import core.framework.api.http.HTTPStatus;
import core.framework.http.HTTPHeaders;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * Stable download entry for core-ai-desktop: 307 to the newest installer of the caller's platform, or to the
 * releases page when the OS is unknown or the release lookup fails.
 *
 * @author Stephen
 */
public class DesktopDownloadController implements Controller {
    @Inject
    DesktopReleaseService desktopReleaseService;

    @Override
    public Response execute(Request request) {
        var platform = DesktopReleaseService.platform(
                request.queryParams().get("platform"),
                request.header("User-Agent").orElse(null));
        return Response.redirect(desktopReleaseService.downloadUrl(platform), HTTPStatus.TEMPORARY_REDIRECT)
                .header(HTTPHeaders.CACHE_CONTROL, "no-store");
    }
}
