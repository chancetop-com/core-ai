package ai.core.cli.tool;

import ai.core.cli.hub.HubArgumentBuilder;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @author stephen
 */
@Command(name = "call", description = "Execute a local builtin tool (media generation, image/PDF/video understanding)")
class ToolCallCommand extends ToolCommandBase {
    @Parameters(index = "0", paramLabel = "name", description = "Builtin tool name, e.g. generate_image")
    String name;

    @Option(names = "--args", description = "Arguments as a JSON object string")
    String argsJson;

    @Option(names = "--args-file", description = "Read arguments JSON from a file ('-' for stdin)")
    Path argsFile;

    @Option(names = "--arg", description = "Single argument key=value (repeatable, coerced by input_schema)")
    List<String> args;

    @Option(names = "--timeout", description = "Execution timeout in seconds (default: the tool's own timeout)")
    Integer timeoutSeconds;

    @Option(names = "--session", description = "Session id for media attribution and video references (defaults to CORE_AI_SESSION_ID)")
    String session;

    @Override
    protected Integer execute() {
        var builtin = runtime().tool(name).orElseThrow(() -> new HubCliError(
                HubExitCodes.NOT_FOUND, "unknown builtin tool: " + name + " (run 'core-ai-cli tool list')"));
        var arguments = new HubArgumentBuilder().build(argsJson, readArgsFile(), args, builtin.inputSchema());
        var seconds = timeoutSeconds(builtin, timeoutSeconds);
        var startedAt = System.currentTimeMillis();
        var result = runtime().execute(builtin, arguments, seconds, runtime().context(sessionId(session), builtin.name()));
        return print(response(result, newCallId(), System.currentTimeMillis() - startedAt));
    }

    private String readArgsFile() {
        if (argsFile == null) return null;
        try {
            if ("-".equals(argsFile.toString())) return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            return Files.readString(argsFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HubCliError(HubExitCodes.USAGE, "cannot read --args-file: " + e.getMessage(), e);
        }
    }
}
