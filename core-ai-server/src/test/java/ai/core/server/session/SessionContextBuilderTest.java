package ai.core.server.session;

import ai.core.llm.domain.Usage;
import ai.core.media.MediaProvider;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.file.FileService;
import ai.core.server.gateway.ContextualMediaProvider;
import ai.core.server.gateway.GatewayMediaProvider;
import ai.core.server.settings.SystemSettingsService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SessionContextBuilderTest {
    @Test
    void gatewayMediaProviderIsWrappedIntoContextualProvider() {
        var gatewayMediaProvider = mock(GatewayMediaProvider.class);
        var builder = new SessionContextBuilder(mock(ChatArtifactSetup.class), mock(FileService.class),
                mock(PublicUrlConfiguration.class), mock(SystemSettingsService.class), gatewayMediaProvider,
                mock(ApiUserQuotaService.class));

        var context = builder.build("session-1", "user-1");

        var provider = context.getImageMediaProvider();
        assertInstanceOf(ContextualMediaProvider.class, provider);
        assertSame(provider, context.getVideoMediaProvider());
    }

    @Test
    void plainMediaProviderIsWiredDirectly() {
        var mediaProvider = mock(MediaProvider.class);
        var builder = new SessionContextBuilder(mock(ChatArtifactSetup.class), mock(FileService.class),
                mock(PublicUrlConfiguration.class), mock(SystemSettingsService.class), mediaProvider,
                mock(ApiUserQuotaService.class));

        var context = builder.build("session-1", "user-1");

        assertSame(mediaProvider, context.getImageMediaProvider());
        assertSame(mediaProvider, context.getVideoMediaProvider());
    }

    @Test
    void nullMediaProviderLeavesProvidersUnset() {
        var builder = new SessionContextBuilder(mock(ChatArtifactSetup.class), mock(FileService.class),
                mock(PublicUrlConfiguration.class), mock(SystemSettingsService.class), null,
                mock(ApiUserQuotaService.class));

        var context = builder.build("session-1", "user-1");

        assertNull(context.getImageMediaProvider());
        assertNull(context.getVideoMediaProvider());
    }

    @Test
    void tokenUsageIsMeteredAgainstTheSessionUser() {
        var quotaService = mock(ApiUserQuotaService.class);
        var builder = new SessionContextBuilder(mock(ChatArtifactSetup.class), mock(FileService.class),
                mock(PublicUrlConfiguration.class), mock(SystemSettingsService.class), null, quotaService);

        var context = builder.build("session-1", "user-1");
        var usage = new Usage(120, 30, 150);
        context.getTokenCostCallback().accept(usage);

        verify(quotaService).recordUsage("user-1", 120L, 30L);
    }
}
