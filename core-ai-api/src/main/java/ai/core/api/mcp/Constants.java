package ai.core.api.mcp;

/**
 * @author stephen
 */
public class Constants {
    public static final String LATEST_PROTOCOL_VERSION = "2025-03-26";
    public static final String JSONRPC_VERSION = "2.0";

    // ---------------------------
    // Method Names
    // ---------------------------
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";
    public static final String METHOD_PING = "ping";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";
    public static final String METHOD_RESOURCES_LIST = "resources/list";
    public static final String METHOD_RESOURCES_READ = "resources/read";
    public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";
    public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";
    public static final String METHOD_RESOURCES_SUBSCRIBE = "resources/subscribe";
    public static final String METHOD_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";
    public static final String METHOD_PROMPT_LIST = "prompts/list";
    public static final String METHOD_PROMPT_GET = "prompts/get";
    public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";
    public static final String METHOD_LOGGING_SET_LEVEL = "logging/setLevel";
    public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";
    public static final String METHOD_ROOTS_LIST = "roots/list";
    public static final String METHOD_NOTIFICATION_ROOTS_LIST_CHANGED = "notifications/roots/list_changed";
    public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";
}
