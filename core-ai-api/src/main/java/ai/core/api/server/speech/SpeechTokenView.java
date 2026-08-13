package ai.core.api.server.speech;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SpeechTokenView {
    @Property(name = "token")
    public String token;

    @Property(name = "region")
    public String region;
}
