package main

import (
	"encoding/json"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"
)

const (
	hubPathPrefix   = "/hub"
	hubAPIPath      = "/api/sandbox-hub"
	maxHubBodySize  = 20 << 20 // 20 MB, matches the /files/upload ceiling
	hubProxyTimeout = 10 * time.Minute
)

// startHubProxy serves the sandbox-internal hub on a loopback-only listener. Scripts inside the
// sandbox talk to 127.0.0.1:8081/hub/*; this process adds the session token and forwards to
// ${server_url}/api/sandbox-hub/*. It stays on its own listener because :8080 is reachable from
// the pod network without authentication — exposing /hub/* there would let anything that can
// reach the pod borrow the session's identity.
func startHubProxy() {
	port := envOrDefault("HUB_PORT", "8081")
	mux := http.NewServeMux()
	mux.HandleFunc(hubPathPrefix+"/", handleHubProxy(newHubReverseProxy()))
	mux.HandleFunc(hubPathPrefix, handleHubProxy(newHubReverseProxy()))
	go func() {
		addr := "127.0.0.1:" + port
		log.Printf("sandbox hub proxy listening on %s (bound=%t)", addr, hubBound())
		if err := http.ListenAndServe(addr, loggingMiddleware(mux)); err != nil {
			log.Printf("sandbox hub proxy stopped: %v", err)
		}
	}()
}

func handleHubProxy(proxy *httputil.ReverseProxy) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodPost {
			writeHubError(w, http.StatusMethodNotAllowed, "method_not_allowed", "hub proxy accepts GET and POST only")
			return
		}
		if !hubBound() {
			writeHubError(w, http.StatusServiceUnavailable, "not_bound",
				"this sandbox is not bound to a session yet; retry shortly")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxHubBodySize)
		proxy.ServeHTTP(w, r)
	}
}

func newHubReverseProxy() *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		Director: func(r *http.Request) {
			b := currentBinding.Load()
			if b == nil {
				return // unreachable: the handler rejects unbound requests
			}
			target, err := url.Parse(b.ServerURL)
			if err != nil || target.Host == "" {
				log.Printf("invalid bound server_url %q: %v", b.ServerURL, err)
				return
			}
			r.URL.Scheme = target.Scheme
			r.URL.Host = target.Host
			r.URL.Path = hubAPIPath + strings.TrimPrefix(r.URL.Path, hubPathPrefix)
			r.Host = target.Host
			// Overwrite whatever the script sent: the session token is the only credential the
			// server accepts on this path, and a script must not be able to pick another one.
			r.Header.Set("Authorization", "Bearer "+b.Token)
			// The server attributes the call to the sandbox; the SDK's own identification is
			// carried alongside so a call can still be traced back to sdk-python/1.2.3.
			if sdk := r.Header.Get("X-Core-AI-Client"); sdk != "" {
				r.Header.Set("X-Core-AI-Sdk", sdk)
			}
			r.Header.Set("X-Core-AI-Client", "sandbox")
		},
		FlushInterval: -1,
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			log.Printf("hub proxy upstream failure: path=%s error=%v", r.URL.Path, err)
			writeHubError(w, http.StatusBadGateway, "upstream_unavailable", "core-ai-server is unreachable: "+err.Error())
		},
		Transport: &http.Transport{
			ResponseHeaderTimeout: hubProxyTimeout,
			IdleConnTimeout:       90 * time.Second,
		},
	}
}

func writeHubError(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]any{"error": code, "message": message})
}
