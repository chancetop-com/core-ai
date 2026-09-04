package ai.core.cli.hub;

import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.api.server.mcphub.HubContentPart;
import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * @author stephen
 */
@Command(name = "call", description = "Execute an MCP tool on the server")
class McpCallCommand extends HubCommandBase {
    private static final int DEFAULT_MAX_OUTPUT = 64 * 1024;

    @Parameters(index = "0", paramLabel = "server/tool", description = "Qualified tool name, e.g. jira/create_issue")
    String qualified;

    @Option(names = "--args", description = "Arguments as a JSON object string")
    String argsJson;

    @Option(names = "--args-file", description = "Read arguments JSON from a file ('-' for stdin)")
    Path argsFile;

    @Option(names = "--arg", description = "Single argument key=value (repeatable, coerced by input_schema)")
    List<String> args;

    @Option(names = "--timeout", description = "Server-side wait limit in seconds (default 60, max 300)")
    Integer timeoutSeconds;

    @Option(names = "--out-dir", description = "Directory for image content files (default: current dir)")
    Path outDir;

    @Option(names = "--max-output", description = "Truncate printed text after N chars (default 65536)")
    Integer maxOutput;

    @Override
    protected Integer execute() {
        var parts = qualified.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new HubCliError(HubExitCodes.USAGE, "expected <server>/<tool>, got: " + qualified);
        }
        var client = client();
        String schemaJson = null;
        if (args != null && !args.isEmpty()) {
            schemaJson = client.describe(parts[0], parts[1]).inputSchema;
        }
        var argumentsJson = new HubArgumentBuilder().build(argsJson, readArgsFile(), args, schemaJson);
        metadata("calling " + qualified + " ...");
        var response = client.call(parts[0], parts[1], argumentsJson, timeoutSeconds);

        boolean failed = Boolean.TRUE.equals(response.isError);
        if (options.json) {
            HubRenderer.printCallJson(response);
        } else if (options.raw) {
            printText(response.text, true);
        } else {
            printText(response.text, false);
            metadata("duration: " + response.durationMs + "ms, server_state: " + response.serverState);
            if (!failed) saveImageContent(response, outDir);
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

    private void saveImageContent(HubCallResponse response, Path outDirectory) {
        if (response.content == null || outDirectory == null) return;
        for (HubContentPart part : response.content) {
            if (part.data == null || part.data.isBlank()) continue;
            try {
                var dir = outDirectory.toAbsolutePath();
                Files.createDirectories(dir);
                var extension = extensionOf(part.mimeType);
                var target = dir.resolve("mcp-image-" + response.callId + extension);
                Files.write(target, Base64.getDecoder().decode(part.data));
                metadata("image saved: " + target);
            } catch (IllegalArgumentException | IOException e) {
                metadata("warning: failed to save image content: " + e.getMessage());
            }
        }
    }

    private String extensionOf(String mimeType) {
        if (mimeType == null) return ".bin";
        return switch (mimeType.toLowerCase(java.util.Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }
}
