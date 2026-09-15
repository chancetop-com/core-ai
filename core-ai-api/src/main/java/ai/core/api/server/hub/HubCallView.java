package ai.core.api.server.hub;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * One hub call execution. Arguments/results are never stored in full, only a hash and a truncated
 * preview; {@code success} is null when the row was inserted but never completed (interrupted run).
 *
 * @author stephen
 */
public class HubCallView {
    @Property(name = "id")
    public String id;

    @Property(name = "kind")
    public String kind;

    @Property(name = "source")
    public String source;

    @Property(name = "target")
    public String target;

    @Property(name = "group")
    public String group;

    @Property(name = "name")
    public String name;

    @Property(name = "refId")
    public String refId;

    @Property(name = "userId")
    public String userId;

    @Property(name = "userType")
    public String userType;

    @Property(name = "userName")
    public String userName;

    @Property(name = "userEmail")
    public String userEmail;

    @Property(name = "success")
    public Boolean success;

    @Property(name = "isError")
    public Boolean isError;

    @Property(name = "statusCode")
    public Integer statusCode;

    @Property(name = "durationMs")
    public Long durationMs;

    @Property(name = "outputBytes")
    public Integer outputBytes;

    @Property(name = "errorMessage")
    public String errorMessage;

    @Property(name = "argsHash")
    public String argsHash;

    @Property(name = "argsPreview")
    public String argsPreview;

    @Property(name = "taskId")
    public String taskId;

    @Property(name = "contextId")
    public String contextId;

    @Property(name = "inputTokens")
    public Long inputTokens;

    @Property(name = "outputTokens")
    public Long outputTokens;

    @Property(name = "createdAt")
    public ZonedDateTime createdAt;
}
