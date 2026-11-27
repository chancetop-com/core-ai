package ai.core.cli.trace;

/**
 * @author Xander
 */
public interface TraceUploader {
    void upload(CliTraceRequest request);

    default void start(CliTraceStart start) {
    }

    default void span(CliTraceSpan span) {
    }
}
