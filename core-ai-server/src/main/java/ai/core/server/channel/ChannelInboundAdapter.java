package ai.core.server.channel;

import core.framework.web.Request;
import core.framework.web.Response;

import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ChannelInboundAdapter {

    String type();

    Optional<Response> handleChallenge(Request request, Map<String, String> config);

    void verify(Request request, Map<String, String> config);

    InboundEvent parseEvent(Request request, Map<String, String> config);
}
