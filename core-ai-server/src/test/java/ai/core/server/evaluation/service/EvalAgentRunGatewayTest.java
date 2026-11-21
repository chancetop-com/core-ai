package ai.core.server.evaluation.service;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.RunStatus;
import ai.core.server.evaluation.domain.EvalEnums;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class EvalAgentRunGatewayTest {
    private final EvalAgentRunGateway gateway = new EvalAgentRunGateway();

    @Test
    void cancellationExceptionIsNotAnAgentFailure() {
        var run = new AgentRun();
        run.status = RunStatus.FAILED;
        run.error = "java.util.concurrent.CancellationException";
        assertEquals(EvalEnums.ExecutionResult.CANCELLED, gateway.toOutcome(run).result());
    }

    @Test
    void maxTurnsIsAgentFailure() {
        var run = new AgentRun();
        run.status = RunStatus.COMPLETED;
        run.error = "max turns reached: 2";
        assertEquals(EvalEnums.ExecutionResult.AGENT_FAILED, gateway.toOutcome(run).result());
    }
}
