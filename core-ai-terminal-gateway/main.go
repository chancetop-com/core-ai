// Package main implements the sandbox terminal gateway: the sole public
// entry point for the v2 direct-connect terminal transport. It verifies a
// server-signed, one-shot ticket, then proxies a WebSocket connection
// straight through to the cluster-internal sandbox runtime that minted it,
// pumping frames verbatim in both directions. See docs/superpowers/specs/
// 2026-08-28-sandbox-interactive-terminal-design.md, "v2: Direct-Connect
// Transport", for the full design.
package main

import (
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// gatewayVersion is injected at build time via
// -ldflags "-X main.gatewayVersion=...", mirroring
// core-ai-sandbox-runtime's runtimeVersion.
var gatewayVersion = "dev"

// pumpWriteTimeout bounds every forwarded-frame write in the pump loop (see
// pump below). 15s is generous for a single WebSocket frame even at the
// runtime's 128KiB hard frame cap, while still bounding how long a
// half-dead peer (TCP alive, not reading) can wedge a pump goroutine open.
const pumpWriteTimeout = 15 * time.Second

// proxyReadLimitBytes caps the size of a single WebSocket frame the gateway
// will read from either side of the proxy. The runtime already enforces its
// own 128KiB read limit, but without a matching limit here an authenticated
// client (ticket auth happens before Upgrade, so this is about a misbehaving
// or compromised holder of a valid ticket, not an anonymous attacker) could
// still send an arbitrarily large single frame straight at the gateway and
// have it buffered fully into gateway memory before the runtime ever sees
// it. gorilla's ReadMessage enforces this by auto-closing the connection
// with close code 1009 (message too big) once a frame exceeds the limit.
const proxyReadLimitBytes = 256 << 10 // 256KiB

// upgrader has no origin check: the signed, one-shot ticket verified before
// Upgrade is called is the sole auth boundary for this endpoint (see the v2
// design doc's threat model). A browser page from any origin holding a valid
// ticket is exactly as trusted as one served from an "expected" origin, so
// Origin carries no security meaning here.
var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

// ---- Nonce one-shot store ----

// nonceStore rejects ticket replay: each ticket's nonce may be redeemed
// exactly once. It is per-process memory only (the design doc defers
// multi-replica replay protection until the gateway scales past one
// instance), guarded by a mutex since HTTP handlers run concurrently.
type nonceStore struct {
	mu   sync.Mutex
	seen map[string]int64 // nonce -> ticket exp (epoch seconds), for later purge
}

func newNonceStore() *nonceStore {
	return &nonceStore{seen: make(map[string]int64)}
}

// redeem records nonce as spent and returns true, or returns false if it was
// already spent (a replay).
func (s *nonceStore) redeem(nonce string, exp int64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, seen := s.seen[nonce]; seen {
		return false
	}
	s.seen[nonce] = exp
	return true
}

// purgeExpired drops entries whose ticket has expired. Tickets live 30s, so
// an entry only needs to survive slightly past its own exp to keep rejecting
// replays of a now-dead ticket; purging keeps the map from growing forever.
func (s *nonceStore) purgeExpired(now int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for nonce, exp := range s.seen {
		if exp <= now {
			delete(s.seen, nonce)
		}
	}
}

func (s *nonceStore) purgeLoop(interval time.Duration) {
	for range time.Tick(interval) {
		s.purgeExpired(time.Now().Unix())
	}
}

// ---- Gateway server ----

// gatewayServer holds the dependencies handleTerminal needs. Kept as a
// struct instead of package globals so tests can spin up independent
// instances, each with its own secret and nonce store.
type gatewayServer struct {
	secret []byte
	nonces *nonceStore
	dialer *websocket.Dialer
}

func newGatewayServer(secret []byte) *gatewayServer {
	return &gatewayServer{
		secret: secret,
		nonces: newNonceStore(),
		dialer: websocket.DefaultDialer,
	}
}

func (g *gatewayServer) routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/terminal", g.handleTerminal)
	mux.HandleFunc("/health", handleHealth)
	return mux
}

// handleTerminal implements GET /terminal?ticket=&client_id=&rows=&cols=&last_seq=.
// Every rejection before Upgrade is a plain HTTP 403; a rejected request
// never becomes a WebSocket connection, so none of these failures are ever
// surfaced as a WebSocket close code.
func (g *gatewayServer) handleTerminal(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	clientID := q.Get("client_id")

	ticket, err := VerifyTicket(q.Get("ticket"), g.secret, time.Now().Unix())
	if err != nil {
		http.Error(w, "invalid ticket", http.StatusForbidden)
		return
	}
	if clientID != ticket.Cid {
		http.Error(w, "client_id does not match ticket", http.StatusForbidden)
		return
	}
	if !g.nonces.redeem(ticket.Nonce, ticket.Exp) {
		http.Error(w, "ticket already used", http.StatusForbidden)
		return
	}

	clientConn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("terminal gateway: client upgrade failed sid=%s sbid=%s: %v", ticket.Sid, ticket.Sbid, err)
		return
	}
	clientConn.SetReadLimit(proxyReadLimitBytes)

	runtimeConn, _, err := g.dialer.Dial(runtimeWsURL(ticket, q), nil)
	if err != nil {
		log.Printf("terminal gateway: runtime dial failed sid=%s sbid=%s: %v", ticket.Sid, ticket.Sbid, err)
		writeCloseFrame(clientConn, websocket.CloseInternalServerErr, "runtime unreachable")
		clientConn.Close()
		return
	}
	runtimeConn.SetReadLimit(proxyReadLimitBytes)

	log.Printf("terminal gateway: connection open sid=%s sbid=%s", ticket.Sid, ticket.Sbid)
	clientCode, runtimeCode := runPumps(clientConn, runtimeConn)
	log.Printf("terminal gateway: connection closed sid=%s sbid=%s clientCloseCode=%d runtimeCloseCode=%d",
		ticket.Sid, ticket.Sbid, clientCode, runtimeCode)
}

// runtimeWsURL builds the runtime's WS endpoint URL. client_id/rows/cols/
// last_seq are forwarded verbatim from the browser's OWN query string, not
// from the ticket (only client_id is cross-checked against the ticket,
// above); the runtime itself validates rows/cols bounds.
func runtimeWsURL(ticket Ticket, q url.Values) string {
	forwarded := url.Values{}
	forwarded.Set("client_id", q.Get("client_id"))
	forwarded.Set("rows", q.Get("rows"))
	forwarded.Set("cols", q.Get("cols"))
	if lastSeq := q.Get("last_seq"); lastSeq != "" {
		forwarded.Set("last_seq", lastSeq)
	}
	u := url.URL{
		Scheme:   "ws",
		Host:     fmt.Sprintf("%s:%d", ticket.IP, ticket.Port),
		Path:     "/terminal/ws",
		RawQuery: forwarded.Encode(),
	}
	return u.String()
}

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}

// ---- Frame pump ----
//
// Writer-ownership model: there are two connections (client, runtime) and
// two pump goroutines, one per direction. pump(src, dst) is the ONLY
// goroutine that ever calls dst.WriteMessage / dst.WriteControl - it both
// forwards src's frames to dst and, when src's read ends, writes dst's close
// frame - so per gorilla's one-writer-per-connection rule no lock is needed
// around those writes. Concretely: pump(client, runtime) owns every write to
// runtime; pump(runtime, client) owns every write to client. Each pump also
// closes BOTH connections on its way out, but Conn.Close is not a "write" in
// gorilla's sense (it just tears down the underlying socket) and is safe to
// call from any goroutine regardless of write ownership; closing the peer's
// connection here is what unblocks the peer pump's blocked ReadMessage so it
// exits too, without needing an extra signaling channel.
func pump(src, dst *websocket.Conn) int {
	defer src.Close()
	defer dst.Close()
	for {
		msgType, data, err := src.ReadMessage()
		if err != nil {
			code := 0
			if closeErr, ok := err.(*websocket.CloseError); ok {
				code = closeErr.Code
				writeCloseFrame(dst, code, closeErr.Text)
			}
			return code
		}
		// A write deadline is required here: without one, a half-dead peer
		// (TCP alive but not reading - a frozen browser tab, a network
		// stall with no RST) leaves WriteMessage blocked forever. That
		// would hang this goroutine's loop permanently, so its defers
		// (closing both conns) never run and the peer pump never unblocks
		// either. pumpWriteTimeout is generous for a single frame capped at
		// maxTerminalWsFrameBytes-equivalent size (128KiB on the runtime
		// side); a deadline error here takes the same return-0 path as any
		// other write failure, which tears down both connections normally.
		if err := dst.SetWriteDeadline(time.Now().Add(pumpWriteTimeout)); err != nil {
			return 0
		}
		if err := dst.WriteMessage(msgType, data); err != nil {
			return 0
		}
	}
}

// runPumps runs both directions to completion and returns the close code
// observed on each side's read (0 when the read error was not a clean
// *websocket.CloseError, e.g. an abrupt TCP drop).
func runPumps(clientConn, runtimeConn *websocket.Conn) (clientCode, runtimeCode int) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); clientCode = pump(clientConn, runtimeConn) }()
	go func() { defer wg.Done(); runtimeCode = pump(runtimeConn, clientConn) }()
	wg.Wait()
	return clientCode, runtimeCode
}

func writeCloseFrame(conn *websocket.Conn, code int, reason string) {
	deadline := time.Now().Add(2 * time.Second)
	_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason), deadline)
}

// ---- main ----

func main() {
	secret := os.Getenv("TICKET_SECRET")
	if secret == "" {
		log.Fatal("TICKET_SECRET is required")
	}
	listen := envOrDefault("LISTEN", ":8080")

	gw := newGatewayServer([]byte(secret))
	go gw.nonces.purgeLoop(time.Minute)

	log.Printf("terminal gateway version=%s listening on %s", gatewayVersion, listen)
	if err := http.ListenAndServe(listen, gw.routes()); err != nil {
		log.Fatalf("terminal gateway: server failed: %v", err)
	}
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
