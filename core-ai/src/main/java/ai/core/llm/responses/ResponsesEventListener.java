package ai.core.llm.responses;

@FunctionalInterface
public interface ResponsesEventListener {
    void onEvent(String type, String dataJson);
}
