package ai.core.api.mcp;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum MethodEnum {
    @Property(name = "initialize")
    METHOD_INITIALIZE,

    @Property(name = "notifications/initialized")
    METHOD_NOTIFICATION_INITIALIZED,

    @Property(name = "notifications/cancelled")
    METHOD_NOTIFICATION_CANCELLED,

    @Property(name = "ping")
    METHOD_PING,

    @Property(name = "tools/list")
    METHOD_TOOLS_LIST,

    @Property(name = "tools/call")
    METHOD_TOOLS_CALL,

    @Property(name = "notifications/tools/list_changed")
    METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,

    @Property(name = "resources/list")
    METHOD_RESOURCES_LIST,

    @Property(name = "resources/read")
    METHOD_RESOURCES_READ,

    @Property(name = "notifications/resources/list_changed")
    METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED,

    @Property(name = "resources/templates/list")
    METHOD_RESOURCES_TEMPLATES_LIST,

    @Property(name = "resources/subscribe")
    METHOD_RESOURCES_SUBSCRIBE,

    @Property(name = "resources/unsubscribe")
    METHOD_RESOURCES_UNSUBSCRIBE,

    @Property(name = "prompts/list")
    METHOD_PROMPT_LIST,

    @Property(name = "prompts/get")
    METHOD_PROMPT_GET,

    @Property(name = "notifications/prompts/list_changed")
    METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED,

    @Property(name = "logging/setLevel")
    METHOD_LOGGING_SET_LEVEL,

    @Property(name = "notifications/message")
    METHOD_NOTIFICATION_MESSAGE,

    @Property(name = "roots/list")
    METHOD_ROOTS_LIST,

    @Property(name = "notifications/roots/list_changed")
    METHOD_NOTIFICATION_ROOTS_LIST_CHANGED,

    @Property(name = "sampling/createMessage")
    METHOD_SAMPLING_CREATE_MESSAGE
}