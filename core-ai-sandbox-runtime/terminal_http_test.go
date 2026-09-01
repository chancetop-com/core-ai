package main

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// b64 encodes to standard Base64, ignoring impossible errors.
func b64(b []byte) string {
	return base64.StdEncoding.EncodeToString(b)
}

// decodeB64 decodes standard Base64, returning "" on error (test helper only).
func decodeB64(s string) string {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return ""
	}
	return string(b)
}

func newTestServer(t *testing.T) (*httptest.Server, *TerminalRegistry) {
	t.Helper()
	useTempWorkspace(t)
	reg := NewTerminalRegistry(10 * time.Minute)
	mux := http.NewServeMux()
	registerTerminalHandlers(mux, reg)
	srv := httptest.NewServer(mux)
	t.Cleanup(func() {
		srv.Close()
		if cur := reg.currentForTest(); cur != nil {
			reg.Remove(cur.ID())
		}
	})
	return srv, reg
}

func createTerminal(t *testing.T, srv *httptest.Server, clientID string) string {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"client_id": clientID, "rows": 24, "cols": 80})
	resp, err := http.Post(srv.URL+"/terminal", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("create status = %d", resp.StatusCode)
	}
	var out struct {
		TerminalID string `json:"terminal_id"`
	}
	json.NewDecoder(resp.Body).Decode(&out)
	return out.TerminalID
}

func TestHttpCreateInputAndStream(t *testing.T) {
	srv, _ := newTestServer(t)
	id := createTerminal(t, srv, "c1")

	// base64 of "echo http-ok\n"
	payload, _ := json.Marshal(map[string]string{"data_base64": "ZWNobyBodHRwLW9rCg=="})
	resp, err := http.Post(srv.URL+"/terminal/"+id+"/input", "application/json", bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 204 {
		t.Fatalf("input status = %d", resp.StatusCode)
	}

	streamResp, err := http.Get(srv.URL + "/terminal/" + id + "/events")
	if err != nil {
		t.Fatal(err)
	}
	defer streamResp.Body.Close()
	if ct := streamResp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("content type = %s", ct)
	}
	scanner := bufio.NewScanner(streamResp.Body)
	deadline := time.After(5 * time.Second)
	found := make(chan bool, 1)
	go func() {
		var sawReady bool
		for scanner.Scan() {
			line := scanner.Text()
			if strings.HasPrefix(line, "event: ready") {
				sawReady = true
			}
			if sawReady && strings.HasPrefix(line, "data: ") && strings.Contains(decodeB64(line[6:]), "http-ok") {
				found <- true
				return
			}
		}
	}()
	select {
	case <-found:
	case <-deadline:
		t.Fatal("never saw echoed output on the stream")
	}
}

func TestHttpBusyAndLimits(t *testing.T) {
	srv, reg := newTestServer(t)
	id := createTerminal(t, srv, "c1")
	reg.Get(id).Subscribe() // simulate an attached browser
	defer reg.Get(id).Unsubscribe()

	body, _ := json.Marshal(map[string]any{"client_id": "c2", "rows": 24, "cols": 80})
	resp, _ := http.Post(srv.URL+"/terminal", "application/json", bytes.NewReader(body))
	resp.Body.Close()
	if resp.StatusCode != 429 {
		t.Fatalf("second client status = %d, want 429", resp.StatusCode)
	}

	big := make([]byte, 90000) // > 64KiB decoded
	payload, _ := json.Marshal(map[string]string{"data_base64": b64(big)})
	resp2, _ := http.Post(srv.URL+"/terminal/"+id+"/input", "application/json", bytes.NewReader(payload))
	resp2.Body.Close()
	if resp2.StatusCode != 400 {
		t.Fatalf("oversized input status = %d, want 400", resp2.StatusCode)
	}

	badSize, _ := json.Marshal(map[string]any{"rows": 3, "cols": 80})
	req, _ := http.NewRequest(http.MethodPut, srv.URL+"/terminal/"+id+"/size", bytes.NewReader(badSize))
	resp3, _ := http.DefaultClient.Do(req)
	resp3.Body.Close()
	if resp3.StatusCode != 400 {
		t.Fatalf("bad resize status = %d, want 400", resp3.StatusCode)
	}
}

// TestHttpOverflowFrame generates enough terminal output to evict history
// from the 512KiB ring, then asserts the SSE frame order on a fresh replay
// from Last-Event-ID: 0 is ready, then exactly one overflow frame, then data
// (output) frames.
func TestHttpOverflowFrame(t *testing.T) {
	srv, reg := newTestServer(t)
	id := createTerminal(t, srv, "c1")
	term := reg.Get(id)

	// ~1MB of raw output base64-encodes to well over the ring's 512KiB budget.
	cmd := "yes | head -c 1000000; echo overflow-done\n"
	payload, _ := json.Marshal(map[string]string{"data_base64": b64([]byte(cmd))})
	resp, err := http.Post(srv.URL+"/terminal/"+id+"/input", "application/json", bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 204 {
		t.Fatalf("input status = %d", resp.StatusCode)
	}

	// Poll the ring directly until it reports eviction relative to a fixed
	// cursor of 0 (matching what Last-Event-ID: 0 below will see). A cursor
	// that tracks the latest-seen seq would never observe overflow: it stays
	// ahead of evictedThrough by construction, since it advances in lockstep
	// with the events actually being drained.
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

	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/terminal/"+id+"/events", nil)
	req.Header.Set("Last-Event-ID", "0")
	streamResp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer streamResp.Body.Close()

	type frame struct{ eventType, data string }
	const wantFrames = 6 // ready + overflow + a handful of data frames is enough evidence of ordering
	resultCh := make(chan []frame, 1)
	go func() {
		scanner := bufio.NewScanner(streamResp.Body)
		var frames []frame
		var pendingType string
		for scanner.Scan() {
			line := scanner.Text()
			switch {
			case strings.HasPrefix(line, "event: "):
				pendingType = strings.TrimPrefix(line, "event: ")
			case strings.HasPrefix(line, "data: ") && pendingType != "":
				frames = append(frames, frame{eventType: pendingType, data: strings.TrimPrefix(line, "data: ")})
				pendingType = ""
				if len(frames) >= wantFrames {
					resultCh <- frames
					return
				}
			}
		}
		resultCh <- frames
	}()

	var frames []frame
	select {
	case frames = <-resultCh:
	case <-time.After(5 * time.Second):
		t.Fatal("did not receive enough SSE frames before timeout")
	}

	if len(frames) < 3 {
		t.Fatalf("got %d frames, want at least ready+overflow+one data frame: %+v", len(frames), frames)
	}
	if frames[0].eventType != "ready" {
		t.Fatalf("first frame = %+v, want event: ready", frames[0])
	}
	if frames[1].eventType != "overflow" {
		t.Fatalf("second frame = %+v, want event: overflow", frames[1])
	}
	overflowCount := 0
	for _, f := range frames {
		if f.eventType == "overflow" {
			overflowCount++
		}
	}
	if overflowCount != 1 {
		t.Fatalf("saw %d overflow frames, want exactly 1: %+v", overflowCount, frames)
	}
	for i := 2; i < len(frames); i++ {
		if frames[i].eventType != "output" {
			t.Fatalf("frame[%d] = %+v, want a data (output) frame after the overflow frame", i, frames[i])
		}
	}
}

func TestHttpLastEventIdReplay(t *testing.T) {
	srv, _ := newTestServer(t)
	id := createTerminal(t, srv, "c1")
	payload, _ := json.Marshal(map[string]string{"data_base64": "ZWNobyByZXBsYXktbWFya2VyCg=="}) // echo replay-marker
	http.Post(srv.URL+"/terminal/"+id+"/input", "application/json", bytes.NewReader(payload))
	time.Sleep(time.Second) // let output land in the ring

	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/terminal/"+id+"/events", nil)
	req.Header.Set("Last-Event-ID", "0")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	scanner := bufio.NewScanner(resp.Body)
	deadline := time.After(5 * time.Second)
	found := make(chan bool, 1)
	go func() {
		for scanner.Scan() {
			line := scanner.Text()
			if strings.HasPrefix(line, "data: ") && strings.Contains(decodeB64(line[6:]), "replay-marker") {
				found <- true
				return
			}
		}
	}()
	select {
	case <-found:
	case <-deadline:
		t.Fatal("replay from Last-Event-ID=0 never delivered earlier output")
	}
}
