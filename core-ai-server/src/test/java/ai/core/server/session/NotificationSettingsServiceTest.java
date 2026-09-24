package ai.core.server.session;

import ai.core.api.server.user.NotificationSettingsView;
import ai.core.api.server.user.UpdateNotificationSettingsRequest;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelConfigView;
import ai.core.server.channel.ChannelOutboundAdapter;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSettingsServiceTest {
    private static final String USER_ID = "u-1";

    @Test
    void getOffersOnlyChannelsThatCouldActuallyDeliver() {
        var harness = harness();
        harness.user.notifyChannelId = "qq";
        harness.user.notifyRecipient = "qqbot:c2c:OPENID";
        harness.user.notifyMinMinutes = 3;
        harness.channels.put("qq", channel("qq", "openclaw", Boolean.TRUE, USER_ID));
        harness.channels.put("disabled", channel("disabled", "openclaw", Boolean.FALSE, null));
        harness.channels.put("someone-else", channel("someone-else", "telegram", Boolean.TRUE, "u-2"));
        harness.channels.put("team", channel("team", "slack", Boolean.TRUE, null));
        when(harness.registry.outbound("openclaw")).thenReturn(mock(ChannelOutboundAdapter.class));
        when(harness.registry.outbound("telegram")).thenReturn(mock(ChannelOutboundAdapter.class));
        when(harness.registry.outbound("slack")).thenThrow(new IllegalArgumentException("no adapter"));

        NotificationSettingsView view = harness.service.get(USER_ID);

        assertEquals("qq", view.channelId);
        assertEquals("qqbot:c2c:OPENID", view.recipient);
        assertEquals(3, view.minMinutes);
        assertEquals(1, view.channels.size());
        assertEquals("qq", view.channels.getFirst().channelId);
        assertEquals("openclaw", view.channels.getFirst().channelType);
    }

    @Test
    void updateStoresTargetAndThreshold() {
        var harness = harness();
        harness.channels.put("qq", channel("qq", "openclaw", Boolean.TRUE, USER_ID));
        when(harness.registry.outbound("openclaw")).thenReturn(mock(ChannelOutboundAdapter.class));

        harness.service.update(USER_ID, request("qq", " qqbot:c2c:OPENID ", 2));

        assertEquals("qq", harness.user.notifyChannelId);
        assertEquals("qqbot:c2c:OPENID", harness.user.notifyRecipient);
        assertEquals(2, harness.user.notifyMinMinutes);
        verify(harness.users).replace(harness.user);
    }

    @Test
    void updateRejectsAChannelThatCannotDeliver() {
        var harness = harness();
        harness.channels.put("qq", channel("qq", "openclaw", Boolean.TRUE, USER_ID));
        when(harness.registry.outbound("openclaw")).thenThrow(new IllegalArgumentException("no adapter"));

        assertThrows(BadRequestException.class, () -> harness.service.update(USER_ID, request("qq", "qqbot:c2c:OPENID", null)));
    }

    @Test
    void updateRejectsAnotherUsersPersonalChannel() {
        var harness = harness();
        harness.channels.put("mine", channel("mine", "openclaw", Boolean.TRUE, "someone-else"));
        when(harness.registry.outbound("openclaw")).thenReturn(mock(ChannelOutboundAdapter.class));

        assertThrows(BadRequestException.class, () -> harness.service.update(USER_ID, request("mine", "qqbot:c2c:OPENID", null)));
    }

    @Test
    void updateRequiresARecipientWhenAChannelIsSelected() {
        var harness = harness();
        harness.channels.put("qq", channel("qq", "openclaw", Boolean.TRUE, USER_ID));
        when(harness.registry.outbound("openclaw")).thenReturn(mock(ChannelOutboundAdapter.class));

        assertThrows(BadRequestException.class, () -> harness.service.update(USER_ID, request("qq", "  ", null)));
    }

    @Test
    void updateRejectsANonPositiveThreshold() {
        var harness = harness();

        assertThrows(BadRequestException.class, () -> harness.service.update(USER_ID, request(null, null, 0)));
    }

    @Test
    void updateWithNoChannelTurnsNotificationsOff() {
        var harness = harness();
        harness.user.notifyChannelId = "qq";
        harness.user.notifyRecipient = "qqbot:c2c:OPENID";

        harness.service.update(USER_ID, request("", "", null));

        assertNull(harness.user.notifyChannelId);
        assertNull(harness.user.notifyRecipient);
    }

    private UpdateNotificationSettingsRequest request(String channelId, String recipient, Integer minMinutes) {
        var request = new UpdateNotificationSettingsRequest();
        request.channelId = channelId;
        request.recipient = recipient;
        request.minMinutes = minMinutes;
        return request;
    }

    private ChannelConfigView channel(String channelId, String channelType, Boolean enabled, String userId) {
        var channel = new ChannelConfigView();
        channel.channelId = channelId;
        channel.channelType = channelType;
        channel.enabled = enabled;
        channel.userId = userId;
        return channel;
    }

    @SuppressWarnings("unchecked")
    private Harness harness() {
        var service = new NotificationSettingsService();
        var users = (MongoCollection<User>) mock(MongoCollection.class);
        var store = mock(ChannelConfigStore.class);
        var registry = mock(ChannelRegistry.class);
        var user = new User();
        user.id = USER_ID;
        Map<String, ChannelConfigView> channels = new LinkedHashMap<>();
        when(users.get(USER_ID)).thenReturn(Optional.of(user));
        when(store.all()).thenAnswer(invocation -> Map.copyOf(channels));
        when(store.load(anyString())).thenAnswer(invocation -> channels.get((String) invocation.getArgument(0)));

        service.userCollection = users;
        service.channelConfigStore = store;
        service.channelRegistry = registry;
        return new Harness(service, users, user, channels, registry);
    }

    private record Harness(NotificationSettingsService service, MongoCollection<User> users, User user,
                           Map<String, ChannelConfigView> channels, ChannelRegistry registry) {
    }
}
