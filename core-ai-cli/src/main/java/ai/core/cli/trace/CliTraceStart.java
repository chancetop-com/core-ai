package ai.core.cli.trace;

/**
 * Pure data carrier for the trace start payload ({@code POST /api/traces/ingest/start}).
 * Serialized via plain maps on the upload thread so GraalVM native image requires no reflection metadata.
 *
 * @author stephen
 */
public class CliTraceStart {
    public String traceId;
    public String name;
    public String input;
    public Long startedAtEpochMs;
    public String serviceName;
    public String serviceVersion;
    public String environment;
    public String clientType;
}
