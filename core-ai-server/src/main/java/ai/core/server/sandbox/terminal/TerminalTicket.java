package ai.core.server.sandbox.terminal;

/**
 * Immutable payload of a one-shot sandbox terminal ticket. Minted by
 * {@link TerminalTicketCodec#mint} on core-ai-server and verified by the Go
 * terminal gateway (core-ai-terminal-gateway/ticket.go) before it dials the
 * runtime WS endpoint at {@code ip:port}.
 * <p>
 * Field order here has no bearing on the wire format; the wire format's key
 * order is fixed by {@link TerminalTicketCodec#mint} directly.
 *
 * @param sid   chat session id
 * @param sbid  sandbox id
 * @param cid   terminal client id (must equal the browser's WS client_id)
 * @param ip    resolved sandbox runtime pod address
 * @param port  resolved sandbox runtime port
 * @param iat   issued-at, epoch seconds
 * @param exp   expiry, epoch seconds (iat + 30s)
 * @param nonce 32 hex chars (16 random bytes), one-shot replay guard
 * @author xander
 */
public record TerminalTicket(String sid, String sbid, String cid, String ip, int port, long iat, long exp, String nonce) {
}
