package ai.core.context;

/**
 * @author xander
 */
public interface CompressionListener {
    void onCompression(int beforeCount, int afterCount, boolean completed);
}
