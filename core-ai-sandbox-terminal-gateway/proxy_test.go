package main

import (
	"bytes"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

const proxyTestSecret = "test-secret-0123456789abcdef"

// ---- Test helpers ----

// newEchoRuntimeServer stubs the runtime's /terminal/ws endpoint: it echoes
// every frame back verbatim, preserving message type.
func newEchoRuntimeServer(t *testing.T) *httptest.Server {
	t.Helper()
	up := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		for {
			msgType, data, err := conn.ReadMessage()
			if err != nil {
				return
			}
			if err := conn.WriteMessage(msgType, data); err != nil {
				return
			}
		}
	}))
}

// newImmediateCloseRuntimeServer stubs a runtime that upgrades and then
// immediately sends a close frame with the given code/reason, simulating
// e.g. the real runtime's 4003 "busy" rejection.
func newImmediateCloseRuntimeServer(t *testing.T, code int, reason string) *httptest.Server {
	t.Helper()
	up := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := up.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		writeCloseFrame(conn, code, reason)
	}))
}

// hostPort splits an httptest.Server URL into the host/port the gateway
// should dial (as it would dial an in-cluster runtime pod IP/port).
func hostPort(t *testing.T, rawURL string) (string, int) {
	t.Helper()
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("parse url %q: %v", rawURL, err)
	}
	host, portStr, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatalf("split host:port %q: %v", u.Host, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("parse port %q: %v", portStr, err)
	}
	return host, port
}

// unusedPort returns a TCP port on localhost that nothing is listening on,
// simulating an unreachable runtime.
func unusedPort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	if err := l.Close(); err != nil {
		t.Fatalf("close listener: %v", err)
	}
	return port
}

func toWsURL(httpURL string) string {
	return "ws" + strings.TrimPrefix(httpURL, "http")
}

// newTestGateway starts a gateway test server signing/verifying with
// proxyTestSecret.
func newTestGateway(t *testing.T) *httptest.Server {
	t.Helper()
	gw := newGatewayServer([]byte(proxyTestSecret))
	srv := httptest.NewServer(gw.routes())
	t.Cleanup(srv.Close)
	return srv
}

// mintValidTicket builds a fresh, unexpired, uniquely-nonced ticket pointing
// at ip:port, signed with proxyTestSecret.
func mintValidTicket(cid, ip string, port int, nonce string) (Ticket, string) {
	now := time.Now().Unix()
	ticket := Ticket{
		Sid:   "s-" + nonce,
		Sbid:  "sb-" + nonce,
		Cid:   cid,
		IP:    ip,
		Port:  port,
		Iat:   now,
		Exp:   now + 30,
		Nonce: nonce,
	}
	return ticket, mintTicketForTest(ticket, []byte(proxyTestSecret))
}

func terminalURL(gwURL, ticketStr, clientID string) string {
	return gwURL + "/terminal?ticket=" + url.QueryEscape(ticketStr) +
		"&client_id=" + url.QueryEscape(clientID) + "&rows=24&cols=80"
}

// ---- Tests ----

func TestProxyEndToEndEcho(t *testing.T) {
	runtimeSrv := newEchoRuntimeServer(t)
	defer runtimeSrv.Close()
	ip, port := hostPort(t, runtimeSrv.URL)

	_, ticketStr := mintValidTicket("client-echo", ip, port, "n-echo-000000000000000000000000")
	gwSrv := newTestGateway(t)

	conn, _, err := websocket.DefaultDialer.Dial(toWsURL(terminalURL(gwSrv.URL, ticketStr, "client-echo")), nil)
	if err != nil {
		t.Fatalf("dial proxy: %v", err)
	}
	defer conn.Close()

	if err := conn.WriteMessage(websocket.TextMessage, []byte("hello")); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := conn.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatalf("set read deadline: %v", err)
	}
	_, data, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if string(data) != "hello" {
		t.Fatalf("got %q, want %q", data, "hello")
	}
}

// TestProxyPreservesBinaryMessageType locks the "pump verbatim, preserving
// message type" claim for the BinaryMessage case specifically: the other
// echo test only exercises TextMessage.
func TestProxyPreservesBinaryMessageType(t *testing.T) {
	runtimeSrv := newEchoRuntimeServer(t)
	defer runtimeSrv.Close()
	ip, port := hostPort(t, runtimeSrv.URL)

	_, ticketStr := mintValidTicket("client-binary", ip, port, "n-binary00000000000000000000000")
	gwSrv := newTestGateway(t)

	conn, _, err := websocket.DefaultDialer.Dial(toWsURL(terminalURL(gwSrv.URL, ticketStr, "client-binary")), nil)
	if err != nil {
		t.Fatalf("dial proxy: %v", err)
	}
	defer conn.Close()

	payload := []byte{0x00, 0x01, 0xff, 0xfe, 0x10, 0x20}
	if err := conn.WriteMessage(websocket.BinaryMessage, payload); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := conn.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatalf("set read deadline: %v", err)
	}
	msgType, data, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if msgType != websocket.BinaryMessage {
		t.Fatalf("got message type %d, want BinaryMessage (%d)", msgType, websocket.BinaryMessage)
	}
	if !bytes.Equal(data, payload) {
		t.Fatalf("got %v, want %v", data, payload)
	}
}

func TestProxyRejectsExpiredTicket(t *testing.T) {
	now := time.Now().Unix()
	ticket := Ticket{
		Sid: "s-expired", Sbid: "sb-expired", Cid: "client-expired",
		IP: "127.0.0.1", Port: 9,
		Iat: now - 60, Exp: now - 30,
		Nonce: "n-expired0000000000000000000000",
	}
	ticketStr := mintTicketForTest(ticket, []byte(proxyTestSecret))
	gwSrv := newTestGateway(t)

	resp, err := http.Get(terminalURL(gwSrv.URL, ticketStr, "client-expired"))
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("got status %d, want %d", resp.StatusCode, http.StatusForbidden)
	}
}

func TestProxyRejectsNonceReplay(t *testing.T) {
	runtimeSrv := newEchoRuntimeServer(t)
	defer runtimeSrv.Close()
	ip, port := hostPort(t, runtimeSrv.URL)

	_, ticketStr := mintValidTicket("client-replay", ip, port, "n-replay000000000000000000000")
	gwSrv := newTestGateway(t)

	conn, _, err := websocket.DefaultDialer.Dial(toWsURL(terminalURL(gwSrv.URL, ticketStr, "client-replay")), nil)
	if err != nil {
		t.Fatalf("first dial should succeed: %v", err)
	}
	conn.Close()

	// Second use of the SAME ticket (same nonce) must be rejected before
	// ever attempting the upgrade.
	resp, err := http.Get(terminalURL(gwSrv.URL, ticketStr, "client-replay"))
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("got status %d, want %d", resp.StatusCode, http.StatusForbidden)
	}
}

func TestProxyRejectsClientIDMismatch(t *testing.T) {
	_, ticketStr := mintValidTicket("client-real", "127.0.0.1", 9, "n-mismatch00000000000000000000")
	gwSrv := newTestGateway(t)

	resp, err := http.Get(terminalURL(gwSrv.URL, ticketStr, "someone-else"))
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("got status %d, want %d", resp.StatusCode, http.StatusForbidden)
	}
}

func TestProxyPropagatesRuntimeCloseCode(t *testing.T) {
	runtimeSrv := newImmediateCloseRuntimeServer(t, 4003, "busy")
	defer runtimeSrv.Close()
	ip, port := hostPort(t, runtimeSrv.URL)

	_, ticketStr := mintValidTicket("client-close", ip, port, "n-close0000000000000000000000")
	gwSrv := newTestGateway(t)

	conn, _, err := websocket.DefaultDialer.Dial(toWsURL(terminalURL(gwSrv.URL, ticketStr, "client-close")), nil)
	if err != nil {
		t.Fatalf("dial proxy: %v", err)
	}
	defer conn.Close()

	if err := conn.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatalf("set read deadline: %v", err)
	}
	_, _, err = conn.ReadMessage()
	var closeErr *websocket.CloseError
	if !errors.As(err, &closeErr) {
		t.Fatalf("expected *websocket.CloseError, got %T: %v", err, err)
	}
	if closeErr.Code != 4003 {
		t.Fatalf("got close code %d, want 4003", closeErr.Code)
	}
}

// TestProxyClosesOnOversizedClientFrame locks the proxyReadLimitBytes
// behavior: a single client frame over the 256KiB gateway read limit trips
// gorilla's built-in "message too big" handling on the client connection's
// ReadMessage, which auto-closes with code 1009 before the oversized frame
// is ever forwarded to the runtime.
func TestProxyClosesOnOversizedClientFrame(t *testing.T) {
	runtimeSrv := newEchoRuntimeServer(t)
	defer runtimeSrv.Close()
	ip, port := hostPort(t, runtimeSrv.URL)

	_, ticketStr := mintValidTicket("client-oversized", ip, port, "n-oversized0000000000000000000")
	gwSrv := newTestGateway(t)

	conn, _, err := websocket.DefaultDialer.Dial(toWsURL(terminalURL(gwSrv.URL, ticketStr, "client-oversized")), nil)
	if err != nil {
		t.Fatalf("dial proxy: %v", err)
	}
	defer conn.Close()

	oversized := make([]byte, proxyReadLimitBytes+1)
	if err := conn.WriteMessage(websocket.BinaryMessage, oversized); err != nil {
		t.Fatalf("write oversized frame: %v", err)
	}

	if err := conn.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatalf("set read deadline: %v", err)
	}
	_, _, err = conn.ReadMessage()
	if err == nil {
		t.Fatal("expected connection to be closed after oversized frame, got nil error")
	}
	var closeErr *websocket.CloseError
	if errors.As(err, &closeErr) {
		if closeErr.Code != websocket.CloseMessageTooBig && closeErr.Code != websocket.CloseAbnormalClosure {
			t.Fatalf("got close code %d, want %d (message too big) or %d (abnormal)",
				closeErr.Code, websocket.CloseMessageTooBig, websocket.CloseAbnormalClosure)
		}
	}
	// A non-CloseError (e.g. an abrupt TCP reset as the gateway tears down
	// the connection mid-oversized-frame) is also an acceptable outcome
	// here: what matters is that the connection did not stay open and echo
	// the oversized frame back.
}

func TestProxyReturns1011WhenRuntimeUnreachable(t *testing.T) {
	port := unusedPort(t)
	_, ticketStr := mintValidTicket("client-unreachable", "127.0.0.1", port, "n-unreachable00000000000000000")
	gwSrv := newTestGateway(t)

	conn, _, err := websocket.DefaultDialer.Dial(toWsURL(terminalURL(gwSrv.URL, ticketStr, "client-unreachable")), nil)
	if err != nil {
		t.Fatalf("client upgrade should still succeed: %v", err)
	}
	defer conn.Close()

	if err := conn.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatalf("set read deadline: %v", err)
	}
	_, _, err = conn.ReadMessage()
	var closeErr *websocket.CloseError
	if !errors.As(err, &closeErr) {
		t.Fatalf("expected *websocket.CloseError, got %T: %v", err, err)
	}
	if closeErr.Code != 1011 {
		t.Fatalf("got close code %d, want 1011", closeErr.Code)
	}
}
