package ai.core.cli.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Engine methods exposed over the app-server protocol; implemented by {@link AppServerEngine} and
 * swapped for a lightweight fake in protocol tests.
 *
 * @author stephen
 */
public interface EngineApi {
    void attach(NotificationSink sink);

    Set<String> supportedMethods();

    JsonNode initialize(ObjectNode params);

    JsonNode call(String method, ObjectNode params);

    void shutdown();
}
