package ai.core.session;

import ai.core.agent.CancellationToken;
import ai.core.api.server.session.ApprovalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author stephen
 */
public class PermissionGate {
    private final Logger logger = LoggerFactory.getLogger(PermissionGate.class);
    private final ConcurrentMap<String, CompletableFuture<ApprovalDecision>> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CancellationToken> parentTokens = new ConcurrentHashMap<>();

    public void prepare(String callId) {
        logger.debug("preparing approval future, callId={}", callId);
        pending.put(callId, new CompletableFuture<>());
    }

    public ApprovalDecision waitForApproval(String callId, long timeoutMs, CancellationToken parentToken) {
        var future = pending.get(callId);
        if (future == null) {
            logger.error("no prepared future for callId={}, this should not happen", callId);
            return ApprovalDecision.DENY;
        }
        logger.debug("waiting for tool approval, callId={}", callId);
        CancellationToken childToken = null;
        if (parentToken != null) {
            parentTokens.put(callId, parentToken);
            childToken = parentToken.createChild();
            childToken.onCancel(() -> {
                future.complete(ApprovalDecision.DENY);
            });
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.warn("approval timeout, callId={}", callId);
            return ApprovalDecision.DENY;
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            logger.error("failed to wait for approval, callId={}", callId, e);
            return ApprovalDecision.DENY;
        } finally {
            pending.remove(callId);
            parentTokens.remove(callId);
            if (childToken != null) {
                childToken.disconnect();
            }
        }
    }

    public void respond(String callId, ApprovalDecision decision) {
        var parentToken = parentTokens.remove(callId);
        var future = pending.get(callId);
        if (future != null) {
            logger.debug("received tool approval response, callId={}, decision={}", callId, decision);
            future.complete(decision);
        } else {
            logger.warn("received response for unknown or expired callId, callId={}", callId);
        }
        if ((decision == ApprovalDecision.DENY || decision == ApprovalDecision.DENY_ALWAYS) && parentToken != null) {
            parentToken.cancel();
        }
    }
}
