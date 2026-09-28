package ai.core.server.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChannelTargetsTest {
    @Test
    void keepsADirectAddress() {
        assertEquals("qqbot:c2c:OPENID", ChannelTargets.directTarget("qqbot:c2c:OPENID"));
    }

    @Test
    void rejectsSharedPlaces() {
        assertNull(ChannelTargets.directTarget("qqbot:group:123456"));
        assertNull(ChannelTargets.directTarget("qqbot:GROUP:123456"));
        assertNull(ChannelTargets.directTarget("discord:guild:42"));
    }

    @Test
    void keepsTargetsThatDoNotDeclareAKind() {
        // Slack channel ids and Telegram chat ids have no kind segment; nothing says they are shared
        assertEquals("C0123ABC", ChannelTargets.directTarget("C0123ABC"));
        assertEquals("12345", ChannelTargets.directTarget("12345"));
        assertEquals("qqbot:agent:main", ChannelTargets.directTarget("qqbot:agent:main"));
    }

    @Test
    void ignoresBlank() {
        assertNull(ChannelTargets.directTarget(null));
        assertNull(ChannelTargets.directTarget("  "));
    }
}
