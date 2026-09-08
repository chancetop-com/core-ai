package ai.core.cli.hub.apitool;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubArgumentBuilder;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
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
@Command(name = "call", description = "Execute a Service API operation on the server")
class ApiToolCallCommand extends HubCommandBase {
    private static final int DEFAULT_MAX_OUTPUT = 64 * 1024;

    @Parameters(index = "0", paramLabel = "app/service/operation", description = "Qualified operation name, e.g. order-service/refund/create")
    String qualified;

    @Option(names = "--args", description = "Arguments as a JSON object string")
    String argsJson;

    @Option(names = "--args-file", description = "Read arguments JSON from a file ('-' for stdin)")
    Path argsFile;

    @Option(names = "--arg", description = "Single argument key=value (repeatable, coerced by input_schema)")
    List<String> args;

    @Option(names = "--timeout", description = "Server-side wait limit in seconds (default 60, max 300)")
    Integer timeoutSeconds;

    @Option(names = "--max-output", description = "Truncate printed text after N chars (default 65536)")
    Integer maxOutput;

    @Override
    protected Integer execute() {
        var client = apiToolClient();
        var parts = ApiToolNameParser.resolve(client, qualified);
        String schemaJson = null;
        if (args != null && !args.isEmpty()) {
            schemaJson = client.describe(parts[0], parts[1], parts[2]).inputSchema;
        }
        var argumentsJson = new HubArgumentBuilder().build(argsJson, readArgsFile(), args, schemaJson);
        metadata("calling " + String.join("/", parts) + " ...");
        var response = client.call(parts[0], parts[1], parts[2], argumentsJson, timeoutSeconds);

        boolean failed = Boolean.TRUE.equals(response.isError);
        if (json()) {
            HubRenderer.printCallJson(response);
        } else if (raw()) {
            printText(response.text, true);
        } else {
            printText(response.text, false);
            String status = response.statusCode == null ? "" : ", status_code: " + response.statusCode;
            metadata("duration: " + response.durationMs + "ms" + status);
        }
        return failed ? HubExitCodes.TOOL_ERROR : HubExitCodes.SUCCESS;
    }

    private String readArgsFile() {
        if (argsFile == null) return null;
        try {
            if ("-".equals(argsFile.toString())) {
                return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return Files.readString(argsFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HubCliError(HubExitCodes.USAGE, "cannot read --args-file: " + e.getMessage(), e);
        }
    }

    private void printText(String text, boolean raw) {
        if (text == null || text.isEmpty()) {
            if (!raw) ConsoleWriter.println();
            return;
        }
        int limit = maxOutput == null || maxOutput <= 0 ? DEFAULT_MAX_OUTPUT : maxOutput;
        String body = text;
        if (text.length() > limit) {
            body = text.substring(0, limit) + "\n... (output truncated; raise --max-output for more)";
            metadata("warning: output truncated at " + limit + " chars");
        }
        ConsoleWriter.print(body);
        if (!body.endsWith("\n")) ConsoleWriter.println();
    }
}
