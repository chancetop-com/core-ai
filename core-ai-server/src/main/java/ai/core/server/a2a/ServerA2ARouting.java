package ai.core.server.a2a;

import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionOwnershipRegistry;

/**
 * Shared A2A routing dependencies configured by the server module.
 *
 * @author xander
 */
final class ServerA2ARouting {
    A2ATaskRegistry taskRegistry;
    SessionOwnershipRegistry ownershipRegistry;
    RpcClient rpcClient;
    A2AEventRelay eventRelay;
}
