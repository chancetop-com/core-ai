package ai.core.cli.appserver;

import ai.core.mcp.client.McpCommandSafety;
import ai.core.mcp.client.McpSecretExpander;
import ai.core.mcp.client.McpServerConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validation and safe error mapping for desktop-managed MCP RPCs.
 *
 * All rejection messages use fixed business codes and safe text without echoing raw exception
 * messages, URLs, or secret values.
 *
 * @author stephen
 */
final class McpApiValidation {
    static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SECRET_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final List<String> RESERVED_SECRET_PREFIXES = List.of("CORE_AI_", "MCP_");

    static String formatRpcRejection(RpcException e) {
        var code = e.data() != null ? e.data().path("code").asText("") : "";
        return code.isEmpty() ? e.getMessage() : code + ": " + e.getMessage();
    }

    static void validateDefinition(String name, Map<String, Object> raw) {
        try {
            var expanded = McpSecretExpander.expand(name, raw, Map.of());
            McpCommandSafety.validate(name, expanded);
            McpServerConfig.fromMap(name, expanded);
        } catch (McpSecretExpander.UnsupportedSecretLocationException e) {
            throw RpcException.business("MCP_SECRET_UNSUPPORTED_LOCATION",
                    "secret placeholders are only allowed in env and headers values", "field", e.fieldName(), e);
        } catch (McpSecretExpander.MissingSecretException e) {
            validateStructure(name, raw);
        } catch (McpCommandSafety.UnsafeHeaderException e) {
            throw RpcException.business("MCP_HEADER_INVALID", "invalid MCP header name or value", e);
        } catch (McpCommandSafety.UnsafeCommandException e) {
            throw RpcException.business("MCP_ARG_UNSAFE", "unsafe MCP command or arguments", e);
        } catch (RuntimeException e) {
            throw RpcException.business("MCP_CONFIG_INVALID", "invalid MCP server configuration", e);
        }
    }

    static void validateStructure(String name, Map<String, Object> raw) {
        try {
            McpCommandSafety.validate(name, raw);
            McpServerConfig.fromMap(name, raw);
        } catch (McpCommandSafety.UnsafeHeaderException e) {
            throw RpcException.business("MCP_HEADER_INVALID", "invalid MCP header name or value", e);
        } catch (McpCommandSafety.UnsafeCommandException e) {
            throw RpcException.business("MCP_ARG_UNSAFE", "unsafe MCP command or arguments", e);
        } catch (RuntimeException e) {
            throw RpcException.business("MCP_CONFIG_INVALID", "invalid MCP server configuration", e);
        }
    }

    static void validateSecret(String serverName, String name, JsonNode value) {
        if (!SECRET_NAME.matcher(name).matches()) {
            throw RpcException.business("MCP_SECRET_NAME_INVALID",
                    "invalid secret name for MCP server '" + serverName + "': " + name);
        }
        for (var prefix : RESERVED_SECRET_PREFIXES) {
            if (name.startsWith(prefix)) {
                throw RpcException.business("MCP_SECRET_NAME_RESERVED",
                        "reserved secret name for MCP server '" + serverName + "': " + name);
            }
        }
        if (!value.isTextual()) {
            throw RpcException.business("MCP_CONFIG_INVALID",
                    "secret " + name + " of MCP server '" + serverName + "' must be a string");
        }
    }

    static String rejectionFor(RuntimeException e) {
        if (e instanceof McpSecretExpander.UnsupportedSecretLocationException) {
            return "MCP_SECRET_UNSUPPORTED_LOCATION: secret placeholders are only allowed in env and headers values";
        }
        if (e instanceof McpSecretExpander.MissingSecretException missing) {
            return "MCP_SECRET_MISSING: missing secret " + missing.secretName();
        }
        if (e instanceof McpCommandSafety.UnsafeHeaderException) {
            return "MCP_HEADER_INVALID: invalid MCP header name or value";
        }
        if (e instanceof McpCommandSafety.UnsafeCommandException) {
            return "MCP_ARG_UNSAFE: unsafe MCP command or arguments";
        }
        return "MCP_CONFIG_INVALID: invalid MCP server configuration";
    }

    private McpApiValidation() {
    }
}
