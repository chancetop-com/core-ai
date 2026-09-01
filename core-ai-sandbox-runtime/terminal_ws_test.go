package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// ---- test helpers ----

func b64(b []byte) string {
	return base64.StdEncoding.EncodeToString(b)
}

func newWsTestServer(t *testing.T) (*httptest.Server, *TerminalRegistry) {
	t.Helper()
	useTempWorkspace(t)
	reg := NewTerminalRegistry(10 * time.Minute)
	mux := http.NewServeMux()
	registerTerminalWs(mux, reg)
	srv := httptest.NewServer(mux)
	t.Cleanup(func() {
		srv.Close()
		if cur := reg.currentForTest(); cur != nil {
			reg.Remove(cur.ID())
		}
	})
	return srv, reg
}

func wsURLFor(srv *httptest.Server, path string) string {
	return "ws" + strings.TrimPrefix(srv.URL, "http") + path
}

func dialTerminalWs(t *testing.T, srv *httptest.Server, clientID string, rows, cols int, lastSeq int64) *websocket.Conn {
	t.Helper()
	u := fmt.Sprintf("%s?client_id=%s&rows=%d&cols=%d&last_seq=%d", wsURLFor(srv, "/terminal/ws"), clientID, rows, cols, lastSeq)
	conn, _, err := websocket.DefaultDialer.Dial(u, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	return conn
}

type wsFrame struct {
	T         string `json:"t"`
	ID        string `json:"id"`
	Recovered bool   `json:"recovered"`
	Seq       int64  `json:"seq"`
	D         string `json:"d"`
	Code      int    `json:"code"`
	M         string `json:"m"`
}

func readWsFrame(t *testing.T, conn *websocket.Conn, timeout time.Duration) wsFrame {
	t.Helper()
	conn.SetReadDeadline(time.Now().Add(timeout))
	_, data, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("ReadMessage: %v", err)
	}
	var f wsFrame
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatalf("unmarshal frame %q: %v", data, err)
	}
	return f
}

func sendInput(t *testing.T, conn *websocket.Conn, s string) {
	t.Helper()
	sendRawInput(t, conn, []byte(s))
}

func sendRawInput(t *testing.T, conn *websocket.Conn, raw []byte) {
	t.Helper()
	b, err := json.Marshal(map[string]string{"t": "i", "d": b64(raw)})
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.WriteMessage(websocket.TextMessage, b); err != nil {
		t.Fatalf("write input frame: %v", err)
	}
}

func sendResize(t *testing.T, conn *websocket.Conn, rows, cols int) {
	t.Helper()
	b, err := json.Marshal(map[string]any{"t": "resize", "rows": rows, "cols": cols})
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.WriteMessage(websocket.TextMessage, b); err != nil {
		t.Fatalf("write resize frame: %v", err)
	}
}

// waitForOutput reads frames until one of type "o" decodes to contain the
// given substring, or the deadline passes.
func waitForOutput(t *testing.T, conn *websocket.Conn, contains string, timeout time.Duration) bool {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		conn.SetReadDeadline(time.Now().Add(timeout))
		_, data, err := conn.ReadMessage()
		if err != nil {
			return false
		}
		var f wsFrame
		if json.Unmarshal(data, &f) != nil {
			continue
		}
		if f.T == "o" {
			b, err := base64.StdEncoding.DecodeString(f.D)
			if err == nil && strings.Contains(string(b), contains) {
				return true
			}
		}
	}
	return false
}

// expectCloseCode drains any data frames until the connection reports a
// WebSocket close error, then asserts its code.
func expectCloseCode(t *testing.T, conn *websocket.Conn, want int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		_, _, err := conn.ReadMessage()
		if err == nil {
			continue
		}
		var ce *websocket.CloseError
		if !errors.As(err, &ce) {
			t.Fatalf("ReadMessage error = %v, want *websocket.CloseError", err)
		}
		if ce.Code != want {
			t.Fatalf("close code = %d, want %d", ce.Code, want)
		}
		return
	}
	t.Fatal("timed out waiting for close frame")
}

// ---- tests ----

func TestWsCreateAndEcho(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn.Close()

	ready := readWsFrame(t, conn, 5*time.Second)
	if ready.T != "ready" || ready.Recovered {
		t.Fatalf("ready frame = %+v, want t=ready recovered=false", ready)
	}

	sendInput(t, conn, "echo ws-ok\n")

	if !waitForOutput(t, conn, "ws-ok", 5*time.Second) {
		t.Fatal("never saw echoed output on the ws stream")
	}
}

func TestWsSameClientRecoversAndMirrors(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn1 := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn1.Close()
	ready1 := readWsFrame(t, conn1, 5*time.Second)
	if ready1.Recovered {
		t.Fatalf("first connect must not be recovered: %+v", ready1)
	}

	conn2 := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn2.Close()
	ready2 := readWsFrame(t, conn2, 5*time.Second)
	if !ready2.Recovered || ready2.ID != ready1.ID {
		t.Fatalf("same client must recover same terminal: ready2=%+v want id=%s recovered=true", ready2, ready1.ID)
	}

	sendInput(t, conn2, "echo mirror-ok\n")

	if !waitForOutput(t, conn1, "mirror-ok", 5*time.Second) {
		t.Fatal("conn1 never saw output mirrored from conn2's input")
	}
	if !waitForOutput(t, conn2, "mirror-ok", 5*time.Second) {
		t.Fatal("conn2 never saw its own echoed output")
	}
}

func TestWsBusyWhileConnected(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn1 := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn1.Close()
	readWsFrame(t, conn1, 5*time.Second)

	conn2 := dialTerminalWs(t, srv, "c2", 24, 80, 0)
	defer conn2.Close()
	expectCloseCode(t, conn2, 4003)
}

func TestWsReplayFromLastSeq(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn1 := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn1.Close()
	readWsFrame(t, conn1, 5*time.Second)
	sendInput(t, conn1, "echo replay-marker\n")
	if !waitForOutput(t, conn1, "replay-marker", 5*time.Second) {
		t.Fatal("setup: never saw replay-marker the first time")
	}

	// Same client id always recovers (regardless of subscriber count), so
	// conn1 need not be closed for this second connection to attach to the
	// same terminal and replay from last_seq=0.
	conn2 := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn2.Close()
	ready2 := readWsFrame(t, conn2, 5*time.Second)
	if !ready2.Recovered {
		t.Fatalf("expected recover on same client id, got %+v", ready2)
	}
	if !waitForOutput(t, conn2, "replay-marker", 5*time.Second) {
		t.Fatal("replay from last_seq=0 never delivered earlier output")
	}
}

func TestWsInvalidParamsCloses4001(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn := dialTerminalWs(t, srv, "c1", 3, 80, 0) // rows=3 is below the 5-200 bound
	defer conn.Close()
	expectCloseCode(t, conn, 4001)
}

func TestWsOversizedInputCloses1008(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn.Close()
	readWsFrame(t, conn, 5*time.Second)

	big := make([]byte, 90000) // > 64KiB decoded cap
	sendRawInput(t, conn, big)

	expectCloseCode(t, conn, websocket.ClosePolicyViolation) // 1008
}

// TestWsOversizedInputClosesWhileWriterBusy is the regression test for the
// critical fix: it must NOT be possible to pass this test by accident of Go's
// select ordering the way an idle-terminal version could. It keeps the writer
// goroutine mid-flush (never parked in its idle select, racing peerGone
// against closeSig) by starting a continuous output burst immediately before
// sending the oversized frame, then drains output frames until the close
// error surfaces and asserts it is still exactly 1008 - proving the fix
// removed the race rather than merely avoiding it in the common case.
func TestWsOversizedInputClosesWhileWriterBusy(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn.Close()
	readWsFrame(t, conn, 5*time.Second)

	sendInput(t, conn, "yes | head -c 5000000\n") // keeps the writer busy flushing "o" frames
	big := make([]byte, 90000)                    // > 64KiB decoded cap
	sendRawInput(t, conn, big)

	expectCloseCode(t, conn, websocket.ClosePolicyViolation) // 1008
}

func TestWsInvalidResizeCloses4001(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn.Close()
	readWsFrame(t, conn, 5*time.Second)

	sendResize(t, conn, 3, 80) // rows=3 is below the 5-200 bound
	expectCloseCode(t, conn, 4001)
}

func TestWsResizeAppliesToStty(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn.Close()
	readWsFrame(t, conn, 5*time.Second)

	sendResize(t, conn, 40, 120)
	sendInput(t, conn, "stty size\n")

	if !waitForOutput(t, conn, "40 120", 5*time.Second) {
		t.Fatal("resized dimensions never appeared via stty size")
	}
}

// TestWsOverflowFrame generates enough terminal output to evict history from
// the 512KiB ring, then asserts the frame order on a fresh recover from
// last_seq=0: ready, then exactly one overflow frame, then output frames.
func TestWsOverflowFrame(t *testing.T) {
	srv, reg := newWsTestServer(t)
	conn1 := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	ready1 := readWsFrame(t, conn1, 5*time.Second)
	conn1.Close() // only needed to create the terminal; avoid draining ~1MB over the wire

	term := reg.Get(ready1.ID)
	if term == nil {
		t.Fatal("terminal missing from registry")
	}
	cmd := "yes | head -c 1000000; echo overflow-done\n"
	if err := term.WriteInput([]byte(cmd)); err != nil {
		t.Fatal(err)
	}

	// Poll the ring directly until it reports eviction relative to a fixed
	// cursor of 0, matching what last_seq=0 below will see.
	overflowed := false
	var waitCursor int64
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if _, ov := term.EventsSince(0); ov {
			overflowed = true
			break
		}
		events, _ := term.EventsSince(waitCursor)
		if len(events) > 0 {
			waitCursor = events[len(events)-1].Seq
		}
		term.WaitForEventAfter(waitCursor, 200*time.Millisecond)
	}
	if !overflowed {
		t.Fatal("ring never evicted history; test setup produced insufficient output")
	}

	conn2 := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn2.Close()
	ready2 := readWsFrame(t, conn2, 5*time.Second)
	if !ready2.Recovered {
		t.Fatalf("expected recover, got %+v", ready2)
	}
	overflow := readWsFrame(t, conn2, 5*time.Second)
	if overflow.T != "overflow" {
		t.Fatalf("first frame after ready = %+v, want t=overflow", overflow)
	}
	dataFrame := readWsFrame(t, conn2, 5*time.Second)
	if dataFrame.T != "o" {
		t.Fatalf("frame after overflow = %+v, want t=o", dataFrame)
	}
}

func TestWsExitDeliversExitFrameAndCloses1000(t *testing.T) {
	srv, _ := newWsTestServer(t)
	conn := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn.Close()
	readWsFrame(t, conn, 5*time.Second)

	sendInput(t, conn, "exit 3\n")

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		_, data, err := conn.ReadMessage()
		if err != nil {
			var ce *websocket.CloseError
			if errors.As(err, &ce) && ce.Code == websocket.CloseNormalClosure {
				return
			}
			t.Fatalf("ReadMessage error = %v, want a normal (1000) close after exit", err)
		}
		var f wsFrame
		if json.Unmarshal(data, &f) == nil && f.T == "exit" {
			if f.Code != 3 {
				t.Fatalf("exit code = %d, want 3", f.Code)
			}
		}
	}
	t.Fatal("never observed exit frame followed by a normal close")
}

// TestWsThroughLoggingMiddleware guards the main.go wiring requirement: the
// WebSocket upgrade must survive being wrapped by loggingMiddleware, which
// requires loggingResponseWriter to forward http.Hijacker.
func TestWsThroughLoggingMiddleware(t *testing.T) {
	useTempWorkspace(t)
	reg := NewTerminalRegistry(10 * time.Minute)
	mux := http.NewServeMux()
	registerTerminalWs(mux, reg)
	srv := httptest.NewServer(loggingMiddleware(mux))
	t.Cleanup(func() {
		srv.Close()
		if cur := reg.currentForTest(); cur != nil {
			reg.Remove(cur.ID())
		}
	})

	conn := dialTerminalWs(t, srv, "c1", 24, 80, 0)
	defer conn.Close()
	ready := readWsFrame(t, conn, 5*time.Second)
	if ready.T != "ready" {
		t.Fatalf("ready frame = %+v, want t=ready (upgrade must succeed through logging middleware)", ready)
	}
}
