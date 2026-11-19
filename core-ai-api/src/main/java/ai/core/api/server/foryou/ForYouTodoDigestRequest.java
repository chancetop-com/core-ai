package ai.core.api.server.foryou;

import core.framework.api.web.service.QueryParam;

/**
 * @author core-ai
 */
public class ForYouTodoDigestRequest {
    /** yyyy-MM-dd; defaults to today in Asia/Shanghai. */
    @QueryParam(name = "date")
    public String date;
}
