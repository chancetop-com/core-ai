package main

import (
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"time"

	"github.com/gorilla/websocket"
)

const maxTerminalInputBytes = 64 * 1024

// maxTerminalInputBase64Bytes bounds the base64-encoded "d" field BEFORE it is
// decoded, so an oversized input frame is rejected without first paying for a
// base64.DecodeString allocation sized by attacker-controlled input. 87400 is
// comfortably above the exact base64 ceiling for 64KiB decoded
// (ceil(65536/3)*4 = 87384) so no legal frame is ever rejected by this check
// alone; maxTerminalInputBytes below remains the precise belt-and-braces cap
// on the decoded length.
const maxTerminalInputBase64Bytes = 87400

// maxTerminalWsFrameBytes is a hard backstop passed to conn.SetReadLimit so a
// single grossly oversized frame is never fully buffered by ReadMessage /
// json.Unmarshal before any application-level check runs. It sits well above
// maxTerminalInputBase64Bytes plus JSON envelope overhead, so it never fires
// for a frame the application-level check would otherwise reject with a
// proper close code; it only protects against frames large enough to matter
// for memory (a client sending many MB in one frame).
const maxTerminalWsFrameBytes = 128 * 1024

// wsUpgrader has no auth of its own: the gateway's signed ticket is the auth
// boundary (see the v2 design), and origin is meaningless for a pod-internal
// connection dialed by the gateway rather than a browser.
var wsUpgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

func validSize(rows, cols int) bool {
	return rows >= 5 && rows <= 200 && cols >= 20 && cols <= 500
}

// ---- Frame schema (exact wire contract with the gateway/frontend) ----

type wsReadyFrame struct {
	T         string `json:"t"`
	ID        string `json:"id"`
	Recovered bool   `json:"recovered"`
}

type wsOutputFrame struct {
	T   string `json:"t"`
	Seq int64  `json:"seq"`
	D   string `json:"d"`
}

type wsOverflowFrame struct {
	T string `json:"t"`
}

type wsExitFrame struct {
	T    string `json:"t"`
	Code int    `json:"code"`
}

type wsErrFrame struct {
	T string `json:"t"`
	M string `json:"m"`
}

// wsInboundFrame covers both client->server frame shapes; unused fields are
// simply left zero-valued by json.Unmarshal for whichever "t" is not present.
type wsInboundFrame struct {
	T    string `json:"t"`
	D    string `json:"d"`
	Rows int    `json:"rows"`
	Cols int    `json:"cols"`
}

// registerTerminalWs wires the WebSocket terminal endpoint onto the given mux.
// Called from main() with http.DefaultServeMux and from tests with a private mux.
func registerTerminalWs(mux *http.ServeMux, reg *TerminalRegistry) {
	mux.HandleFunc("/terminal/ws", func(w http.ResponseWriter, r *http.Request) {
		handleTerminalWs(w, r, reg)
	})
}

// parseTerminalWsParams reads and validates client_id/rows/cols/last_seq from
// the query string. last_seq defaults to 0 when absent.
func parseTerminalWsParams(r *http.Request) (clientID string, rows, cols uint16, lastSeq int64, ok bool) {
	q := r.URL.Query()
	clientID = q.Get("client_id")
	rowsInt, rowsErr := strconv.Atoi(q.Get("rows"))
	colsInt, colsErr := strconv.Atoi(q.Get("cols"))
	if clientID == "" || rowsErr != nil || colsErr != nil || !validSize(rowsInt, colsInt) {
		return "", 0, 0, 0, false
	}
	if v := q.Get("last_seq"); v != "" {
		parsed, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			return "", 0, 0, 0, false
		}
		lastSeq = parsed
	}
	return clientID, uint16(rowsInt), uint16(colsInt), lastSeq, true
}

// handleTerminalWs upgrades the connection first (so protocol/validation
// failures can be reported as a real WebSocket close code, per the spec's
// frame/close-code contract) and only then validates params and resolves the
// terminal. Up to this point exactly one goroutine (this one) touches conn,
// so writing directly here does not violate the single-writer discipline
// documented on serveTerminalWs below.
func handleTerminalWs(w http.ResponseWriter, r *http.Request, reg *TerminalRegistry) {
	conn, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("terminal ws upgrade failed: %v", err)
		return
	}
	// Backstop against a single grossly oversized frame being fully buffered
	// before any application-level check runs; see maxTerminalWsFrameBytes.
	conn.SetReadLimit(maxTerminalWsFrameBytes)

	clientID, rows, cols, lastSeq, ok := parseTerminalWsParams(r)
	if !ok {
		writeCloseFrame(conn, 4001, "invalid params")
		conn.Close()
		return
	}

	t, recovered, err := reg.CreateOrRecover(clientID, rows, cols)
	if err == ErrTerminalBusy {
		writeCloseFrame(conn, 4003, "busy")
		conn.Close()
		return
	}
	if err != nil {
		writeJSONFrame(conn, wsErrFrame{T: "err", M: err.Error()})
		writeCloseFrame(conn, websocket.CloseInternalServerErr, "terminal start failed")
		conn.Close()
		return
	}

	t.Subscribe()
	defer t.Unsubscribe()

	serveTerminalWs(conn, t, recovered, lastSeq)
}

// ---- Writer-goroutine discipline ----
//
// gorilla/websocket allows only one concurrent writer per connection (the
// Close/WriteControl methods are documented as safe to call concurrently with
// everything else, but this code deliberately does not rely on that carve-out:
// EVERY write to conn - including close frames - happens on a single writer
// goroutine (terminalWriterLoop). The reader goroutine (terminalReaderLoop)
// never calls a conn.Write* method; when it needs the connection closed (a
// protocol violation), it sends a request over the closeSig channel and lets
// the writer goroutine perform the actual close. This keeps the invariant
// "only one goroutine ever writes to conn" trivially true by construction,
// rather than depending on a doc comment in a third-party library.
//
// The two goroutines are joined via two channels:
//   - closeSig: reader -> writer, "please close with this code/reason".
//   - peerGone: closed by the reader when ReadMessage returns an error (client
//     disconnected or sent its own close frame), so the writer's live-tail wait
//     unblocks immediately instead of waiting out its up-to-15s timeout.

type wsCloseSignal struct {
	code   int
	reason string
}

// serveTerminalWs runs the reader loop on the calling goroutine and the
// writer loop on a spawned goroutine, and returns once both have finished.
func serveTerminalWs(conn *websocket.Conn, t *Terminal, recovered bool, lastSeq int64) {
	closeSig := make(chan wsCloseSignal, 1)
	peerGone := make(chan struct{})

	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		terminalWriterLoop(conn, t, recovered, lastSeq, closeSig, peerGone)
	}()

	terminalReaderLoop(conn, t, closeSig, peerGone)
	<-writerDone
}

// terminalReaderLoop is the connection's sole reader. It never writes to
// conn; a protocol violation is reported by sending on closeSig and returning
// (which lets the writer perform the close and lets defer conn.Close() in the
// writer tear down the socket for both directions).
//
// peerGone is closed ONLY in the genuine read-error branch (the client
// disconnected or sent its own close frame). It is deliberately NOT closed on
// the protocol-violation returns below: a blanket `defer close(peerGone)`
// would race the writer's `select { case <-peerGone: ...; case
// <-closeSig: ... }` whenever the writer is mid-flush (not yet parked in that
// select) when a violation lands - Go picks pseudo-randomly between two
// simultaneously-ready channels, so the writer could take peerGone and return
// WITHOUT ever writing the 1008/4001 close frame, leaving the client with a
// raw TCP close instead of the documented close code. Never closing peerGone
// on this path makes closeSig the only channel that can ever fire here,
// removing the race entirely rather than relying on scheduling to favor it.
func terminalReaderLoop(conn *websocket.Conn, t *Terminal, closeSig chan<- wsCloseSignal, peerGone chan<- struct{}) {
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			close(peerGone)
			return
		}
		var frame wsInboundFrame
		if err := json.Unmarshal(data, &frame); err != nil {
			continue // ignore malformed frames rather than tearing down the session
		}
		switch frame.T {
		case "i":
			// Reject on the base64 (pre-decode) length first so an oversized
			// frame never pays for a base64.DecodeString allocation sized by
			// attacker-controlled input; the decoded-length check below is
			// belt-and-braces for the exact 64KiB cutoff.
			if len(frame.D) > maxTerminalInputBase64Bytes {
				requestClose(closeSig, websocket.ClosePolicyViolation, "input too large")
				return
			}
			raw, err := base64.StdEncoding.DecodeString(frame.D)
			if err != nil || len(raw) > maxTerminalInputBytes {
				requestClose(closeSig, websocket.ClosePolicyViolation, "input too large")
				return
			}
			// The terminal may have exited concurrently; the writer's exit path
			// already covers reporting that to the client, so the write error
			// here (ErrTerminalExited) needs no separate handling.
			_ = t.WriteInput(raw)
		case "resize":
			if !validSize(frame.Rows, frame.Cols) {
				requestClose(closeSig, 4001, "invalid resize")
				return
			}
			_ = t.Resize(uint16(frame.Rows), uint16(frame.Cols))
		}
	}
}

func requestClose(closeSig chan<- wsCloseSignal, code int, reason string) {
	select {
	case closeSig <- wsCloseSignal{code: code, reason: reason}:
	default: // a close is already pending; one is enough
	}
}

// terminalWriterLoop is the connection's sole writer: it sends the ready
// frame, replays ring history from lastSeq (emitting overflow first if any
// was evicted), then live-tails new events until the terminal exits, the
// reader requests a close, or the peer disconnects.
func terminalWriterLoop(conn *websocket.Conn, t *Terminal, recovered bool, lastSeq int64, closeSig <-chan wsCloseSignal, peerGone <-chan struct{}) {
	defer conn.Close()

	if !writeJSONFrame(conn, wsReadyFrame{T: "ready", ID: t.ID(), Recovered: recovered}) {
		return
	}

	cursor := lastSeq
	firstBatch := true
	for {
		events, overflowed := t.EventsSince(cursor)
		if firstBatch && overflowed {
			if !writeJSONFrame(conn, wsOverflowFrame{T: "overflow"}) {
				return
			}
		}
		firstBatch = false
		for _, e := range events {
			cursor = e.Seq
			switch e.Type {
			case "exit":
				code, _ := strconv.Atoi(e.Data)
				writeJSONFrame(conn, wsExitFrame{T: "exit", Code: code})
				writeCloseFrame(conn, websocket.CloseNormalClosure, "shell exited")
				return
			case "output":
				if !writeJSONFrame(conn, wsOutputFrame{T: "o", Seq: e.Seq, D: e.Data}) {
					return
				}
			}
		}

		// Race the blocking wait against a reader-requested close and peer
		// disconnect, mirroring v1's waitDone select pattern: the orphaned
		// WaitForEventAfter goroutine still drains on its own via its timeout,
		// it's just no longer on this loop's return path.
		waitDone := make(chan struct{})
		go func() {
			t.WaitForEventAfter(cursor, 15*time.Second)
			close(waitDone)
		}()
		select {
		case <-peerGone:
			return
		case sig := <-closeSig:
			writeCloseFrame(conn, sig.code, sig.reason)
			return
		case <-waitDone:
		}
	}
}

func writeJSONFrame(conn *websocket.Conn, v any) bool {
	b, err := json.Marshal(v)
	if err != nil {
		log.Printf("terminal ws: marshal frame: %v", err)
		return false
	}
	return conn.WriteMessage(websocket.TextMessage, b) == nil
}

func writeCloseFrame(conn *websocket.Conn, code int, reason string) {
	deadline := time.Now().Add(2 * time.Second)
	_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason), deadline)
}
