package ai.core.cli.hub;

import picocli.CommandLine.Option;

/**
 * Options shared by every {@code core-ai-cli mcp ...} subcommand.
 *
 * @author stephen
 */
public class HubGlobalOptions {
    @Option(names = "--json", description = "Machine-readable single-line JSON output")
    public boolean json;

    @Option(names = "--raw", description = "Call mode: print only the tool text content to stdout")
    public boolean raw;

    @Option(names = "--server", description = "core-ai-server base URL (overrides CORE_AI_SERVER / auth.json)")
    public String server;

    @Option(names = "--api-key", description = "API key (overrides CORE_AI_API_KEY / auth.json)")
    public String apiKey;

    @Option(names = "--quiet", description = "Suppress progress and metadata output on stderr")
    public boolean quiet;
}
