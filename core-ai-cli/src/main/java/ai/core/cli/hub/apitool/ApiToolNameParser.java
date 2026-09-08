package ai.core.cli.hub.apitool;

import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;

/**
 * Parses the operation operand of {@code describe}/{@code call}:
 * <ul>
 *   <li>{@code app/service/operation} — the canonical three-part name, used directly;</li>
 *   <li>anything without a slash is treated as an existing function name
 *       ({@code app_service_operation}) and must be resolved via the server lookup endpoint;</li>
 *   <li>blank input is a usage error.</li>
 * </ul>
 *
 * @author stephen
 */
final class ApiToolNameParser {
    /** Returns the canonical three-part name; {@code null} means the input looks like a bare function name. */
    static String[] parseQualified(String value) {
        if (value == null || value.isBlank()) {
            throw new HubCliError(HubExitCodes.USAGE, "expected <app>/<service>/<operation>, got empty input");
        }
        if (!value.contains("/")) return null;   // bare tool_name form -> server lookup
        var parts = value.split("/");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new HubCliError(HubExitCodes.USAGE,
                    "expected <app>/<service>/<operation>, got: " + value);
        }
        return parts;
    }

    /** Returns the canonical three-part name; bare function names resolve through the client lookup. */
    static String[] resolve(ApiToolHubClient client, String value) {
        var parts = parseQualified(value);
        if (parts != null) return parts;
        var summary = client.lookup(value).operation;
        if (summary == null || summary.app == null || summary.service == null || summary.name == null) {
            throw new HubCliError(HubExitCodes.NOT_FOUND, "api tool not found: " + value);
        }
        return new String[]{summary.app, summary.service, summary.name};
    }

    private ApiToolNameParser() {
    }
}
