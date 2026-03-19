package ai.core.cli.hook;

public enum HookEvent {
    SESSION_START("SessionStart"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse");

    private final String jsonName;

    HookEvent(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
