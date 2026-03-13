package ai.core.agent.internal;

import ai.core.llm.domain.FunctionCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public class ToolCallFingerprint {
    private final String toolName;
    private final String argumentsHash;

    public ToolCallFingerprint(String toolName, String argumentsHash) {
        this.toolName = toolName;
        this.argumentsHash = argumentsHash;
    }

    public static ToolCallFingerprint of(FunctionCall call) {
        return new ToolCallFingerprint(
                call.function.name,
                hashArguments(call.function.arguments)
        );
    }

    static String hashArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return "";
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(arguments.strip().getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return arguments.strip();
        }
    }

    public String getToolName() {
        return toolName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolCallFingerprint that)) return false;
        return Objects.equals(toolName, that.toolName) && Objects.equals(argumentsHash, that.argumentsHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolName, argumentsHash);
    }
}
