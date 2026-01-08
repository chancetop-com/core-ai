package ai.core.agent.streaming;

/**
 * @author stephen
 */
public class DefaultStreamingCallback implements StreamingCallback {
    @Override
    public void onChunk(String chunk) {

    }

    @Override
    public void onError(Throwable error) {
        throw new RuntimeException(error);
    }
}
