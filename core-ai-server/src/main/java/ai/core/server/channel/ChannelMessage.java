package ai.core.server.channel;

import java.util.Map;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class ChannelMessage {

    public String text;

    public String mediaUrl;

    public String mediaType;

    public Map<String, String> custom;

    public static ChannelMessage text(String text) {
        var msg = new ChannelMessage();
        msg.text = text;
        return msg;
    }

    public static ChannelMessage media(String text, String mediaUrl, String mediaType) {
        var msg = new ChannelMessage();
        msg.text = text;
        msg.mediaUrl = mediaUrl;
        msg.mediaType = mediaType;
        return msg;
    }

    public ChannelMessage with(Consumer<ChannelMessage> modifier) {
        modifier.accept(this);
        return this;
    }
}
