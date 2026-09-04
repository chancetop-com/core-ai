package ai.core.cli.hub.skill;

import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.http.RemoteApiException;

/**
 * Resolves a command-line skill reference to a {@code (namespace, name)} pair.
 * A {@code namespace/name} argument is used as-is; a bare {@code name} goes through
 * the hub {@code lookup} endpoint and maps the outcome to exit codes:
 * single candidate → resolved, none → 5 (not found), several → 2 (ambiguous,
 * server message lists the candidates).
 *
 * @author stephen
 */
public final class SkillNameResolver {
    public QualifiedName resolve(SkillHubClient client, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new HubCliError(HubExitCodes.USAGE, "expected <namespace>/<name> or <name>, got: " + reference);
        }
        if (reference.contains("/")) {
            var parts = reference.split("/", 2);
            if (parts[0].isBlank() || parts[1].isBlank()) {
                throw new HubCliError(HubExitCodes.USAGE, "expected <namespace>/<name>, got: " + reference);
            }
            return new QualifiedName(parts[0], parts[1]);
        }
        try {
            var response = client.lookup(reference);
            if (response.candidates == null || response.candidates.isEmpty()) {
                throw new HubCliError(HubExitCodes.NOT_FOUND, "skill not found: " + reference);
            }
            var candidate = response.candidates.getFirst();
            return new QualifiedName(candidate.namespace, candidate.name);
        } catch (RemoteApiException e) {
            var cliError = toCliError(e, reference);
            if (cliError != null) throw cliError;
            throw e;
        }
    }

    private HubCliError toCliError(RemoteApiException error, String reference) {
        if (error.statusCode == 409) {
            String message = error.getMessage() == null ? "ambiguous skill name: " + reference : error.getMessage();
            return new HubCliError(HubExitCodes.USAGE, message, error);
        }
        if (error.statusCode == 404) {
            return new HubCliError(HubExitCodes.NOT_FOUND, "skill not found: " + reference, error);
        }
        return null;   // other statuses propagate as-is
    }

    public record QualifiedName(String namespace, String name) {
        public String qualifiedName() {
            return namespace + "/" + name;
        }
    }
}
