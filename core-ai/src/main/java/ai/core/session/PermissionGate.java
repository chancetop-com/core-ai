package ai.core.session;

import ai.core.api.session.ApprovalDecision;
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

    // Called by Agent thread - blocks until client responds or timeout
    public ApprovalDecision waitForApproval(String callId, long timeoutMs) {
        var future = new CompletableFuture<ApprovalDecision>();
        pending.put(callId, future);
        logger.info("waiting for tool approval, callId={}", callId);
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
        }
    }

    // Called by API/Client thread - unblocks the agent thread
    public void respond(String callId, ApprovalDecision decision) {
        var future = pending.get(callId);
        if (future != null) {
            logger.info("received tool approval response, callId={}, decision={}", callId, decision);
            future.complete(decision);
        } else {
            logger.warn("received response for unknown or expired callId, callId={}", callId);
        }
    }
}
