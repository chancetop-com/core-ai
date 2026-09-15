package main

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"sync/atomic"
)

// maxBindBodySize caps the /bind payload: it only ever carries a URL, a token and two names.
const maxBindBodySize = 64 << 10

// binding is the session identity handed to this runtime by the server once the session's
// sandbox is ready. It lives in process memory only — never in the pod spec or in a script's
// environment — and is what the loopback hub proxy authorizes with.
type binding struct {
	ServerURL string `json:"server_url"`
	Token     string `json:"token"`
	SessionID string `json:"session_id"`
	AgentName string `json:"agent_name,omitempty"`
	ExpiresAt int64  `json:"expires_at,omitempty"`
}

var currentBinding atomic.Pointer[binding]

// handleBind is called by the server (same listener and trust level as /execute: whoever can
// call /execute can already run arbitrary code in this sandbox, so binding adds no reach).
func handleBind(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPost:
		bindSandbox(w, r)
	case http.MethodDelete:
		currentBinding.Store(nil)
		log.Printf("hub binding cleared")
		w.WriteHeader(http.StatusNoContent)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func bindSandbox(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBindBodySize))
	if err != nil {
		writeHubError(w, http.StatusBadRequest, "bad_request", "failed to read bind body: "+err.Error())
		return
	}
	var b binding
	if err := json.Unmarshal(body, &b); err != nil {
		writeHubError(w, http.StatusBadRequest, "bad_request", "invalid bind body: "+err.Error())
		return
	}
	if b.ServerURL == "" || b.Token == "" {
		writeHubError(w, http.StatusBadRequest, "bad_request", "server_url and token are required")
		return
	}
	currentBinding.Store(&b)
	// The token itself is never logged.
	log.Printf("hub binding stored: server_url=%s session_id=%s agent_name=%s expires_at=%d",
		b.ServerURL, b.SessionID, b.AgentName, b.ExpiresAt)
	w.WriteHeader(http.StatusNoContent)
}

func hubBound() bool {
	return currentBinding.Load() != nil
}
