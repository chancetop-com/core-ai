package ai.core.cli.hub;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.http.RemoteApiException;
import ai.core.cli.hub.skill.SkillHubClient;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * Base class of every {@code core-ai-cli mcp} leaf subcommand: parses credentials,
 * routes rendering by mode and maps failures to the stable exit codes and (in JSON
 * mode) the stdout error envelope {@code {"error":{"code","message","status"}}}.
 *
 * @author stephen
 */
public abstract class HubCommandBase implements Callable<Integer> {
    @Mixin
    HubGlobalOptions options;

    @picocli.CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    protected final HubRenderer renderer = new HubRenderer();

    @Override
    public final Integer call() {
        try {
            return execute();
        } catch (HubCliError e) {
            return fail(e.exitCode, codeFor(e.exitCode), httpStatusFor(e.exitCode), e.getMessage());
        } catch (RemoteApiException e) {
            return fail(HubExitCodes.forException(e), apiCodeFor(e.statusCode), e.statusCode, e.getMessage());
        } catch (IllegalStateException e) {
            var exitCode = HubExitCodes.forException(e);
            String message = e.getMessage() == null ? "request failed" : e.getMessage();
            return fail(exitCode, exitCode == HubExitCodes.TIMEOUT ? "timeout" : "request_failed",
                    exitCode == HubExitCodes.TIMEOUT ? 504 : 0, message);
        } catch (IllegalArgumentException e) {
            return fail(HubExitCodes.USAGE, "bad_request", 400,
                    e.getMessage() == null ? "invalid arguments" : e.getMessage());
        }
    }

    protected abstract Integer execute();

    protected HubClient client() {
        var credentials = credentials();
        return new HubClient(credentials.serverUrl(), credentials.apiKey());
    }

    protected SkillHubClient skillClient() {
        var credentials = credentials();
        return new SkillHubClient(credentials.serverUrl(), credentials.apiKey());
    }

    /** Resolved server URL, for callers that need to record where content came from. */
    protected String serverUrl() {
        return credentials().serverUrl();
    }

    protected boolean json() {
        return options.json;
    }

    protected boolean raw() {
        return options.raw;
    }

    protected HubCredentialResolver.HubCredentials credentials() {
        return new HubCredentialResolver().resolve(options, System.getenv(), HubCredentialResolver.authLookup());
    }

    /** stderr metadata in human/raw mode; nothing in json or quiet mode. */
    protected void metadata(String message) {
        if (options.json || options.quiet) return;
        ConsoleWriter.printError(message);
    }

    /** Single stdout line (human mode). */
    protected void println(String line) {
        ConsoleWriter.println(line);
    }

    private Integer fail(int exitCode, String code, int statusCode, String message) {
        if (options.json) {
            HubRenderer.printErrorJson(statusCode, code, message);
        } else {
            var prefix = exitCode == HubExitCodes.UNAUTHENTICATED
                    ? "not authenticated: " : "error: ";
            ConsoleWriter.printError(prefix + message);
            if (exitCode == HubExitCodes.UNAUTHENTICATED) {
                ConsoleWriter.printError("run 'core-ai-cli --login' to authenticate, or pass --api-key / CORE_AI_API_KEY");
            }
        }
        return exitCode;
    }

    private String codeFor(int exitCode) {
        return switch (exitCode) {
            case HubExitCodes.USAGE -> "bad_request";
            case HubExitCodes.UNAUTHENTICATED -> "unauthorized";
            case HubExitCodes.FORBIDDEN -> "permission_denied";
            case HubExitCodes.NOT_FOUND -> "not_found";
            case HubExitCodes.TIMEOUT -> "timeout";
            default -> "request_failed";
        };
    }

    private int httpStatusFor(int exitCode) {
        return switch (exitCode) {
            case HubExitCodes.USAGE -> 400;
            case HubExitCodes.UNAUTHENTICATED -> 401;
            case HubExitCodes.FORBIDDEN -> 403;
            case HubExitCodes.NOT_FOUND -> 404;
            case HubExitCodes.TIMEOUT -> 504;
            default -> 0;
        };
    }

    private String apiCodeFor(int statusCode) {
        return switch (statusCode) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 403 -> "permission_denied";
            case 404 -> "not_found";
            case 502, 503 -> "server_unavailable";
            case 504 -> "timeout";
            default -> statusCode >= 500 ? "server_error" : "bad_request";
        };
    }
}
