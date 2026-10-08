package ai.core.session;

/**
 * @author stephen
 */
@FunctionalInterface
public interface CustomEventEmitter {
    /** Context variable holding the session's emitter (see {@link SessionCustomEventEmitter}). */
    String CONTEXT_KEY = "__custom_event_emitter";

    /**
     * Sends a named event to the session's listeners.
     *
     * @param card optional platform-defined renderable card (JSON text, see {@link RichCards}); null to send none
     * @return null when the event was dispatched; otherwise a readable reason the emission was rejected
     */
    String emit(String name, String data, String card, String callId);
}
