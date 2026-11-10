package ai.core.server.gateway;

import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionException;
import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRequest;
import ai.core.decision.DecisionResponse;
import ai.core.decision.DecisionType;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class GatewayDecisionControllerTest {
    @Test
    void parsesTheSystemOneBodyShape() {
        var controller = new GatewayDecisionController();

        var request = controller.decisionRequest("""
                {"model":"decision-1",
                 "state":{"message":"down"},
                 "questions":{
                   "team":{"type":"choice","instructions":"Which team?","criteria":{"billing":"Billing","engineering":"Engineering"}},
                   "urgent":{"type":"noul","instructions":"Is it urgent?"},
                   "severity":{"type":"score","instructions":"How severe?","criteria":["Low","Medium","Critical"]}}}""".getBytes(StandardCharsets.UTF_8));

        assertThat(request.model()).isEqualTo("decision-1");
        assertThat(request.state()).contains("\"message\":\"down\"");
        assertThat(request.questions()).extracting(DecisionQuestion::name).containsExactly("team", "urgent", "severity");
        assertThat(request.questions().getFirst().criteria()).containsEntry("engineering", "Engineering");
        assertThat(request.questions().get(1).criteria()).isNull();
        assertThat(request.questions().get(2).levels()).containsExactly("Low", "Medium", "Critical");
    }

    @Test
    void rejectsAnEmptyQuestionMap() {
        var controller = new GatewayDecisionController();

        assertThatThrownBy(() -> controller.decisionRequest("""
                {"state":"x","questions":{}}""".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("questions are required");
    }

    @Test
    void dispatchesThroughTheGatewayAndNormalizesAnswers() {
        var provider = mock(GatewayDecisionProvider.class);
        when(provider.decide(any())).thenReturn(new DecisionResponse(
                Map.of("q", new DecisionAnswer("q", DecisionType.NOUL, 0.25, null, null, null, null, null)),
                "microsoft-decision-1", null));
        var controller = new GatewayDecisionController();
        controller.gatewayDecisionProvider = provider;

        var response = controller.decide("""
                {"state":"x","questions":{"q":{"type":"noul","instructions":"?"}}}""".getBytes(StandardCharsets.UTF_8));

        var captor = ArgumentCaptor.forClass(DecisionRequest.class);
        verify(provider).decide(captor.capture());
        assertThat(captor.getValue().questions()).hasSize(1);
        assertThat(response.answers().get("q").probability()).isEqualTo(0.25);
    }

    @Test
    void mapsUpstreamRateLimitAndClientErrors() {
        var provider = mock(GatewayDecisionProvider.class);
        doThrow(new DecisionException(429, "rate_limited", "slow down")).when(provider).decide(any());
        var controller = new GatewayDecisionController();
        controller.gatewayDecisionProvider = provider;

        assertThatThrownBy(() -> controller.decide("""
                {"state":"x","questions":{"q":{"type":"noul","instructions":"?"}}}""".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(TooManyRequestsException.class);

        doThrow(new DecisionException(422, "unsupported_request_argument", "bad question")).when(provider).decide(any());
        assertThatThrownBy(() -> controller.decide("""
                {"state":"x","questions":{"q":{"type":"noul","instructions":"?"}}}""".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("bad question");
    }

    @Test
    void rejectsMalformedJson() {
        var controller = new GatewayDecisionController();

        assertThatThrownBy(() -> controller.decisionRequest("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid JSON body");
    }

    @Test
    void acceptsEveryQuestionTypeByName() {
        var controller = new GatewayDecisionController();
        var request = controller.decisionRequest("""
                {"state":"x","questions":{
                  "a":{"type":"noul","instructions":"?"},
                  "b":{"type":"choice","criteria":{"x":"X","y":"Y"}},
                  "c":{"type":"score","criteria":["low","high"]}}}""".getBytes(StandardCharsets.UTF_8));

        assertThat(request.questions()).extracting(DecisionQuestion::type)
                .containsExactly(DecisionType.NOUL, DecisionType.CHOICE, DecisionType.SCORE);
    }
}
