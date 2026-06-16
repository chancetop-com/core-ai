package ai.core.cli.trace;

/**
 * @author Xander
 */
public interface TraceUploader {
    void upload(CliTraceRequest request);
}
