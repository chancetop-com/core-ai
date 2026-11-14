package ai.core.server.quality;

import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionOptions;
import ai.core.decision.DecisionProvider;
import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRequest;
import ai.core.decision.DecisionResponse;
import ai.core.decision.DecisionRoute;
import ai.core.decision.DecisionType;
import ai.core.decision.DecisionUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QualityDecisionGatewayTest {
    private static final DecisionRoute ROUTE = new DecisionRoute("provider", "upstream", "route-v1");

    @Test
    void validNoulAnswerIsAccepted() {
        var gateway = gateway(new DecisionResponse(Map.of("q", noul(0.9)), "m", new DecisionUsage(1, 1), ROUTE));
        var result = gateway.decide(null, "state", List.of(DecisionQuestion.noul("q", "i",
            Map.of("yes", "y", "no", "n"))), null, QualityBudget.forTest());
        assertEquals(0.9, result.require("q").probability());
        assertEquals("route-v1", result.route().routeFingerprint());
    }

    @Test
    void missingAnswerFailsClosed() {
        var gateway = gateway(new DecisionResponse(Map.of(), "m", new DecisionUsage(1, 1), ROUTE));
        var error = assertThrows(QualityDecisionException.class, () -> gateway.decide(null, "state",
            List.of(DecisionQuestion.noul("q", "i", Map.of("yes", "y", "no", "n"))), null,
            QualityBudget.forTest()));
        assertEquals(QualityDecisionException.Kind.PROTOCOL, error.kind());
    }

    @Test
    void probabilityOutOfRangeFailsClosed() {
        var gateway = gateway(new DecisionResponse(Map.of("q", noul(1.4)), "m", new DecisionUsage(1, 1), ROUTE));
        var error = assertThrows(QualityDecisionException.class, () -> gateway.decide(null, "state",
            List.of(DecisionQuestion.noul("q", "i", Map.of("yes", "y", "no", "n"))), null,
            QualityBudget.forTest()));
        assertEquals(QualityDecisionException.Kind.PROTOCOL, error.kind());
    }

    @Test
    void unknownAnswerFailsClosed() {
        var gateway = gateway(new DecisionResponse(Map.of("other", noul(0.9)), "m", new DecisionUsage(1, 1), ROUTE));
        var error = assertThrows(QualityDecisionException.class, () -> gateway.decide(null, "state",
            List.of(DecisionQuestion.noul("q", "i", Map.of("yes", "y", "no", "n"))), null,
            QualityBudget.forTest()));
        assertEquals(QualityDecisionException.Kind.PROTOCOL, error.kind());
    }

    @Test
    void routeDriftFailsClosed() {
        var gateway = gateway(new DecisionResponse(Map.of("q", noul(0.9)), "m", new DecisionUsage(1, 1), ROUTE));
        var error = assertThrows(QualityDecisionException.class, () -> gateway.decide(null, "state",
            List.of(DecisionQuestion.noul("q", "i", Map.of("yes", "y", "no", "n"))), "different-route",
            QualityBudget.forTest()));
        assertEquals(QualityDecisionException.Kind.ROUTE_DRIFT, error.kind());
    }

    private QualityDecisionGateway gateway(DecisionResponse response) {
        var gateway = new QualityDecisionGateway();
        gateway.decisionProvider = new DecisionProvider() {
            @Override
            public DecisionResponse decide(DecisionRequest request) {
                return response;
            }

            @Override
            public DecisionResponse decide(DecisionRequest request, DecisionOptions options) {
                return response;
            }
        };
        return gateway;
    }

    private DecisionAnswer noul(double probability) {
        return new DecisionAnswer("q", DecisionType.NOUL, probability, null, null, null, null, null);
    }
}
