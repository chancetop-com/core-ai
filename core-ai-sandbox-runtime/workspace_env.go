package main

import (
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// The workspace env file is declarative, not a shell script: one KEY=VALUE per line, no
// expansion, no quotes. Keeping the parser strict is what makes it safe to inject into every
// child process, including python and MCP servers that never source a shell profile.
const (
	maxWorkspaceEnvFileBytes = 64 * 1024
	maxWorkspaceEnvLineBytes = 4 * 1024
	defaultHomeDir           = "/tmp"
	pathPrependKey           = "PATH_PREPEND"
)

var protectedEnvKeys = map[string]struct{}{
	"HOME":               {},
	"WORKSPACE_DIR":      {},
	"PATH":               {},
	"BASH_ENV":           {},
	"ENV":                {},
	"CDPATH":             {},
	"LD_PRELOAD":         {},
	"LD_LIBRARY_PATH":    {},
	"SHELL":              {},
	"PORT":               {},
	"HUB_PORT":           {},
	"GH_CONFIG_DIR":      {},
	"MAX_ASYNC_TASKS":    {},
	"WORKSPACE_HOME":     {},
	"WORKSPACE_ENV_FILE": {},
}

// core-ai's own variables are reserved as a family; a workspace script must not be able to
// impersonate the runtime's hub or session identity.
func isProtectedEnvKey(key string) bool {
	if _, ok := protectedEnvKeys[key]; ok {
		return true
	}
	return strings.HasPrefix(key, "CORE_AI_")
}

func validEnvKey(key string) bool {
	if key == "" {
		return false
	}
	for i, r := range key {
		switch {
		case r == '_':
		case r >= 'A' && r <= 'Z':
		case r >= 'a' && r <= 'z':
		case i > 0 && r >= '0' && r <= '9':
		default:
			return false
		}
	}
	return true
}

// parseWorkspaceEnv implements the documented file contract: BOM and CRLF tolerated, blank
// lines and comments skipped, an optional `export ` prefix stripped, the first `=` splits, and
// the last duplicate wins. Anything else is reported and ignored instead of guessed at.
func parseWorkspaceEnv(content string) (map[string]string, []string) {
	values := make(map[string]string)
	var warnings []string
	content = strings.TrimPrefix(content, "\ufeff")
	for _, raw := range strings.Split(content, "\n") {
		line := strings.TrimSuffix(raw, "\r")
		if len(line) > maxWorkspaceEnvLineBytes {
			warnings = append(warnings, "env line exceeds 4KiB and was ignored")
			continue
		}
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.HasPrefix(line, "export ") {
			line = strings.TrimSpace(strings.TrimPrefix(line, "export "))
		}
		key, value, found := strings.Cut(line, "=")
		if !found {
			warnings = append(warnings, "env line without '=' was ignored: "+line)
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		if !validEnvKey(key) {
			warnings = append(warnings, "env key is not a valid identifier and was ignored: "+key)
			continue
		}
		if key == pathPrependKey {
			values[key] = value
			continue
		}
		if isProtectedEnvKey(key) {
			warnings = append(warnings, "protected env key was ignored: "+key)
			continue
		}
		values[key] = value
	}
	return values, warnings
}

type workspaceEnvCache struct {
	mu       sync.Mutex
	path     string
	modTime  time.Time
	size     int64
	values   map[string]string
	warnings []string
	loaded   bool
}

var envCache workspaceEnvCache

func readWorkspaceEnvFile(path string) (map[string]string, []string) {
	if path == "" {
		return nil, nil
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, nil
	}
	if info.Size() > maxWorkspaceEnvFileBytes {
		return nil, []string{"env file exceeds 64KiB and was ignored: " + path}
	}

	envCache.mu.Lock()
	defer envCache.mu.Unlock()
	if envCache.loaded && envCache.path == path && envCache.modTime.Equal(info.ModTime()) && envCache.size == info.Size() {
		return envCache.values, envCache.warnings
	}

	content, err := os.ReadFile(path)
	if err != nil {
		return nil, []string{"failed to read env file: " + err.Error()}
	}
	if !utf8.Valid(content) {
		return nil, []string{"env file is not valid UTF-8 and was ignored: " + path}
	}
	values, warnings := parseWorkspaceEnv(string(content))
	envCache.path = path
	envCache.modTime = info.ModTime()
	envCache.size = info.Size()
	envCache.values = values
	envCache.warnings = warnings
	envCache.loaded = true
	for _, warning := range warnings {
		log.Printf("workspace env: %s", warning)
	}
	return values, warnings
}

func workspaceEnvValues() map[string]string {
	values, _ := readWorkspaceEnvFile(os.Getenv("WORKSPACE_ENV_FILE"))
	return values
}

// bashWrapper avoids BASH_ENV leakage and keeps a profile's `set -e` / `exit` out of the tool
// command: the profile is sourced in a subshell, shell options are restored, and only then is
// the caller's command evaluated. The command itself arrives as a positional argument so it is
// never re-quoted by the platform layer.
const bashWrapper = `unset BASH_ENV
saved_opts=$(set +o)
if [ -n "$WORKSPACE_PROFILE_FILE" ] && [ -f "$WORKSPACE_PROFILE_FILE" ]; then
  ( . "$WORKSPACE_PROFILE_FILE" ) 2>>"${WORKSPACE_LOG_DIR:-/tmp}/profile.log" || true
fi
eval "$saved_opts"
eval "$1"`

func workspaceHome() string {
	return envOrDefault("HOME", defaultHomeDir)
}

func ensureWorkspaceDirectories() {
	home := workspaceHome()
	if err := os.MkdirAll(home, 0755); err != nil {
		log.Printf("warning: failed to create home dir %s: %v", home, err)
	}
	if logDir := os.Getenv("WORKSPACE_LOG_DIR"); logDir != "" {
		if err := os.MkdirAll(logDir, 0755); err != nil {
			log.Printf("warning: failed to create workspace log dir %s: %v", logDir, err)
		}
	}
	if envFile := os.Getenv("WORKSPACE_ENV_FILE"); envFile != "" {
		if err := os.MkdirAll(filepath.Dir(envFile), 0755); err != nil {
			log.Printf("warning: failed to create workspace env dir %s: %v", filepath.Dir(envFile), err)
		}
	}
}
