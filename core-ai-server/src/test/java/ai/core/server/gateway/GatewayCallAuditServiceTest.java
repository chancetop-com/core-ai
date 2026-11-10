package ai.core.server.gateway;

import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRequest;
import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.Usage;
import ai.core.server.domain.User;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.user.UserStore;
import ai.core.server.web.auth.AuthContext;
import core.framework.util.StopWatch;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class GatewayCallAuditServiceTest {
    private GatewayCallAuditService service;
    private HubCallAuditService auditService;

    @BeforeEach
    void setUp() {
        service = new GatewayCallAuditService();
        auditService = mock(HubCallAuditService.class);
        var userStore = mock(UserStore.class);
        var webContext = mock(WebContext.class);
        var request = mock(Request.class);
        service.auditService = auditService;
        service.userStore = userStore;
        service.webContext = webContext;

        var user = new User();
        user.userType = "internal";
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        when(webContext.request()).thenReturn(request);
        when(request.header(any(String.class))).thenReturn(Optional.empty());
        when(request.header("X-Core-AI-Client")).thenReturn(Optional.of("cli"));
        when(request.header("x-session-id")).thenReturn(Optional.of("session-7"));
        when(userStore.get("user-1")).thenReturn(Optional.of(user));
        when(auditService.begin(any(HubCallAuditService.BeginRequest.class))).thenReturn("audit-1");
    }

    @Test
    void beginsAMediaRowWithSourceUserTypeSessionAndASanitizedArgumentPreview() {
        var image = new ImageGenerationRequest("gpt-image-2", "a quiet cafe", 2, "1024x1024", "high",
                "png", 90, null, null, null, null, null);

        var callId = service.begin(HubCallAuditService.KIND_MEDIA, "generate_image", "image", image.model(),
                service.imageArguments(image, false), new MediaJobOwner("user-1", "session-7", null));

        assertThat(callId).isEqualTo("audit-1");
        var captor = ArgumentCaptor.forClass(HubCallAuditService.BeginRequest.class);
        verify(auditService).begin(captor.capture());
        var request = captor.getValue();
        assertThat(request.kind()).isEqualTo(HubCallAuditService.KIND_MEDIA);
        assertThat(request.userId()).isEqualTo("user-1");
        assertThat(request.userType()).isEqualTo("internal");
        assertThat(request.source()).isEqualTo("cli");
        assertThat(request.sessionId()).isEqualTo("session-7");
        assertThat(request.target()).isEqualTo("gpt-image-2");
        assertThat(request.group()).isEqualTo("image");
        assertThat(request.name()).isEqualTo("generate_image");
        assertThat(request.argumentsJson()).contains("\"prompt\":\"a quiet cafe\"", "\"n\":2", "\"quality\":\"high\"");
        assertThat(request.argumentsJson()).doesNotContain("base64");
    }

    @Test
    void decisionArgumentsKeepStateOutOfThePreview() {
        var decision = new DecisionRequest("decision-1", "the customer is very upset",
                List.of(DecisionQuestion.noul("urgent", "Is it urgent?", null)));

        var arguments = service.decisionArguments(decision);

        assertThat(arguments).contains("\"state_bytes\":26", "\"questions\":{\"urgent\":\"noul\"}");
        assertThat(arguments).doesNotContain("very upset");
    }

    @Test
    void finishesWithDurationOutputArtifactAndTokenUsage() {
        var response = new ImageGenerationResponse(
                List.of(new ImageData("aW1hZ2U=", null, null)),
                new Usage(10, 1, null, 11, 12, null, null, null, null),
                null, "media-1", List.of());

        service.finish("audit-1", new StopWatch(), GatewayCallAuditService.Result.completed(
                service.imageOutputBytes(response), response.mediaId(),
                service.inputTokens(response.usage()), service.outputTokens(response.usage())));

        verify(auditService).finishWithOutputBytes(eq("audit-1"), anyLong(), eq(8), eq(true), isNull(), isNull());
        verify(auditService).attachResult("audit-1", "media-1", 11L, 12L);
    }

    @Test
    void failureStatusMapsTheGatewayExceptionFamilies() {
        assertThat(service.failureStatus(new BadRequestException("bad"))).isEqualTo(400);
        assertThat(service.failureStatus(new IllegalStateException("boom"))).isEqualTo(500);
    }
}
