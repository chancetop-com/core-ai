package ai.core.server.gateway;

import ai.core.media.domain.MediaReference;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.RemoteMediaLoader;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MediaJob;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier selection is the point of the whole design: nothing chooses a representation without knowing
 * the destination.
 *
 * @author Stephen
 */
class MediaReferenceResolverTest {
    private static final MediaJobOwner OWNER = new MediaJobOwner("user-1", "session-1", null);

    private static GatewayRoute route(String providerId, String protocol) {
        var provider = new GatewayProviderConfig();
        provider.id = providerId;
        provider.type = "kie";
        provider.mediaProtocol = protocol;
        return new GatewayRoute(provider, "bytedance/seedance-2-5");
    }

    private static MediaJob job(String providerId) {
        var job = new MediaJob();
        job.id = "job-1";
        job.userId = "user-1";
        job.sessionId = "session-1";
        job.providerId = providerId;
        job.mediaType = "image";
        job.state = "completed";
        return job;
    }

    private static FileRecord record() {
        var record = new FileRecord();
        record.id = "file-1";
        record.contentType = "image/png";
        return record;
    }

    private final StubJobService jobService = new StubJobService();
    private final RemoteMediaLoader loader = url -> new RemoteMediaLoader.Loaded("downloaded".getBytes(StandardCharsets.UTF_8), "image/jpeg");
    private final MediaReferenceResolver resolver = new MediaReferenceResolver(jobService, loader);

    private MediaReferenceResolver.Resolved resolve(GatewayRoute route) {
        return resolver.resolve(List.of(MediaReference.ofMediaId("gateway-media-v1.img.abc", "char_lin", null)),
                MediaModality.IMAGE, route, OWNER, null);
    }

    @Test
    void tier1ContinuesTheProviderInteractionAndSendsNoReferenceAtAll() {
        jobService.job = job("provider-a");
        jobService.job.upstreamInteractionId = "interaction-9";

        var resolved = resolve(route("provider-a", "VERTEX_GEMINI_INTERACTIONS"));

        assertEquals("interaction-9", resolved.interactionId());
        assertTrue(resolved.references().isEmpty(), "provider-native continuation moves zero bytes");
    }

    @Test
    void tier2ReusesTheProvidersOwnAssetWhenItProducedTheSource() {
        jobService.job = job("provider-a");
        jobService.job.upstreamAssetUrl = "https://kie.example/result.png";
        jobService.record = record();
        jobService.presignedUrl = "https://storage.example/signed";

        var resolved = resolve(route("provider-a", "KIE"));

        assertEquals("https://kie.example/result.png", resolved.references().getFirst().url());
    }

    @Test
    void tier3HandsARemoteUrlProviderAPreSignedUrlItCanActuallyReach() {
        jobService.job = job("provider-b");
        jobService.record = record();
        jobService.presignedUrl = "https://storage.example/signed";

        var resolved = resolve(route("provider-a", "KIE"));

        assertEquals("https://storage.example/signed", resolved.references().getFirst().url());
        assertNull(resolved.references().getFirst().b64Json(), "no bytes are moved on the URL path");
    }

    @Test
    void tier4InlinesForProvidersThatOnlyAcceptInlineData() {
        jobService.job = job("provider-a");
        jobService.job.upstreamAssetUrl = "https://kie.example/result.png";
        jobService.record = record();
        jobService.presignedUrl = "https://storage.example/signed";

        var resolved = resolve(route("provider-a", "OPENAI_IMAGES"));

        var reference = resolved.references().getFirst();
        assertNull(reference.url(), "OpenAI image edits cannot fetch a URL");
        assertTrue(reference.b64Json().startsWith("data:image/png;base64,"));
    }

    @Test
    void carriesTheAuthorTimeNameThroughResolution() {
        jobService.job = job("provider-a");
        jobService.record = record();
        jobService.presignedUrl = "https://storage.example/signed";

        assertEquals("char_lin", resolve(route("provider-a", "KIE")).references().getFirst().name());
    }

    @Test
    void lastFailsWithAnActionableMessageWhenNothingWasGeneratedYet() {
        jobService.job = null;

        var failure = assertThrows(BadRequestException.class, () -> resolver.resolve(
                List.of(MediaReference.ofMediaId(MediaReference.LAST, null, null)),
                MediaModality.IMAGE, route("provider-a", "KIE"), OWNER, null));

        assertTrue(failure.getMessage().contains("generate one first"), failure.getMessage());
    }

    @Test
    void aHandleIsAnAuthorizationCheckUnlikeAnArbitraryUrl() {
        jobService.lookupFailure = new ForbiddenException("media reference does not belong to current user");

        assertThrows(ForbiddenException.class, () -> resolve(route("provider-a", "KIE")));
    }

    @Test
    void externalReferencesAreInlinedOnlyWhenTheDestinationCannotFetchThem() {
        var external = List.of(new MediaReference("https://example.com/a.png", null));

        var forKie = resolver.resolve(external, MediaModality.IMAGE, route("provider-a", "KIE"), OWNER, null);
        assertEquals("https://example.com/a.png", forKie.references().getFirst().url());

        var forOpenAI = resolver.resolve(external, MediaModality.IMAGE, route("provider-a", "OPENAI_IMAGES"), OWNER, null);
        assertNull(forOpenAI.references().getFirst().url());
        assertTrue(forOpenAI.references().getFirst().b64Json().startsWith("data:image/jpeg;base64,"));
    }

    /** Every Mongo-backed method used by the resolver is overridden, so this needs no database. */
    private static final class StubJobService extends MediaJobService {
        MediaJob job;
        FileRecord record;
        String presignedUrl;
        final byte[] bytes = "png-bytes".getBytes(StandardCharsets.UTF_8);
        RuntimeException lookupFailure;

        @Override
        public MediaJob resolveReference(String mediaId, MediaJobOwner owner) {
            if (lookupFailure != null) throw lookupFailure;
            return job;
        }

        @Override
        public Optional<MediaJob> findLatestCompleted(MediaJobOwner owner, MediaModality modality) {
            return Optional.ofNullable(job);
        }

        @Override
        public Optional<FileRecord> fileRecord(MediaJob mediaJob) {
            return Optional.ofNullable(record);
        }

        @Override
        public String downloadUrl(FileRecord fileRecord) {
            return presignedUrl;
        }

        @Override
        public byte[] bytes(FileRecord fileRecord) {
            return bytes;
        }
    }
}
