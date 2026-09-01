package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const maxTerminalInputBytes = 64 * 1024

// registerTerminalHandlers wires terminal routes onto the given mux.
// Called from main() with http.DefaultServeMux and from tests with a private mux.
func registerTerminalHandlers(mux *http.ServeMux, reg *TerminalRegistry) {
	mux.HandleFunc("/terminal", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		handleTerminalCreate(w, r, reg)
	})
	mux.HandleFunc("/terminal/", func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, "/terminal/")
		parts := strings.SplitN(rest, "/", 2)
		id := parts[0]
		action := ""
		if len(parts) == 2 {
			action = parts[1]
		}
		switch {
		case action == "events" && r.Method == http.MethodGet:
			handleTerminalEvents(w, r, reg, id)
		case action == "input" && r.Method == http.MethodPost:
			handleTerminalInput(w, r, reg, id)
		case action == "size" && r.Method == http.MethodPut:
			handleTerminalSize(w, r, reg, id)
		case action == "" && r.Method == http.MethodDelete:
			reg.Remove(id)
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	})
}

func validSize(rows, cols int) bool {
	return rows >= 5 && rows <= 200 && cols >= 20 && cols <= 500
}

func handleTerminalCreate(w http.ResponseWriter, r *http.Request, reg *TerminalRegistry) {
	var req struct {
		ClientID string `json:"client_id"`
		Rows     int    `json:"rows"`
		Cols     int    `json:"cols"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ClientID == "" || !validSize(req.Rows, req.Cols) {
		http.Error(w, `{"error":"invalid request"}`, http.StatusBadRequest)
		return
	}
	t, recovered, err := reg.CreateOrRecover(req.ClientID, uint16(req.Rows), uint16(req.Cols))
	if err == ErrTerminalBusy {
		http.Error(w, `{"error":"terminal busy"}`, http.StatusTooManyRequests)
		return
	}
	if err != nil {
		http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"terminal_id": t.ID(), "recovered": recovered})
}

func handleTerminalInput(w http.ResponseWriter, r *http.Request, reg *TerminalRegistry, id string) {
	t := reg.Get(id)
	if t == nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	var req struct {
		DataBase64 string `json:"data_base64"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	data, err := base64.StdEncoding.DecodeString(req.DataBase64)
	if err != nil || len(data) > maxTerminalInputBytes {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	if err := t.WriteInput(data); err != nil {
		w.WriteHeader(http.StatusGone)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func handleTerminalSize(w http.ResponseWriter, r *http.Request, reg *TerminalRegistry, id string) {
	t := reg.Get(id)
	if t == nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	var req struct {
		Rows int `json:"rows"`
		Cols int `json:"cols"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || !validSize(req.Rows, req.Cols) {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	if err := t.Resize(uint16(req.Rows), uint16(req.Cols)); err != nil {
		w.WriteHeader(http.StatusGone)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func handleTerminalEvents(w http.ResponseWriter, r *http.Request, reg *TerminalRegistry, id string) {
	t := reg.Get(id)
	if t == nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(http.StatusOK)

	cursor := int64(0)
	if lei := r.Header.Get("Last-Event-ID"); lei != "" {
		if v, err := strconv.ParseInt(lei, 10, 64); err == nil {
			cursor = v
		}
	}
	t.Subscribe()
	defer t.Unsubscribe()

	fmt.Fprintf(w, "event: ready\ndata: %s\n\n", t.ID())
	flusher.Flush()

	firstBatch := true
	for {
		events, overflowed := t.EventsSince(cursor)
		if firstBatch && overflowed {
			fmt.Fprintf(w, "event: overflow\ndata: %d\n\n", cursor)
		}
		firstBatch = false
		for _, e := range events {
			fmt.Fprintf(w, "id: %d\nevent: %s\ndata: %s\n\n", e.Seq, e.Type, e.Data)
			cursor = e.Seq
			if e.Type == "exit" {
				flusher.Flush()
				return
			}
		}
		flusher.Flush()
		// Race the blocking wait against client disconnect so a vanished client
		// is observed immediately instead of after up to 15s: the orphaned
		// goroutine below still drains on its own via WaitForEventAfter's
		// timeout, it's just no longer on the return path.
		waitDone := make(chan struct{})
		go func() {
			t.WaitForEventAfter(cursor, 15*time.Second)
			close(waitDone)
		}()
		select {
		case <-r.Context().Done():
			return
		case <-waitDone:
		}
	}
}
