package main

import (
	"encoding/base64"
	"strings"
	"testing"
	"time"
)

// collectOutput drains decoded output until predicate matches or timeout.
func collectOutput(t *testing.T, term *Terminal, contains string, timeout time.Duration) string {
	t.Helper()
	deadline := time.Now().Add(timeout)
	var sb strings.Builder
	var cursor int64
	for time.Now().Before(deadline) {
		events, _ := term.EventsSince(cursor)
		for _, e := range events {
			cursor = e.Seq
			if e.Type == "output" {
				b, _ := base64.StdEncoding.DecodeString(e.Data)
				sb.Write(b)
			}
		}
		if strings.Contains(sb.String(), contains) {
			return sb.String()
		}
		term.WaitForEventAfter(cursor, 200*time.Millisecond)
	}
	t.Fatalf("output never contained %q; got: %q", contains, sb.String())
	return ""
}

func TestTerminalPreservesShellState(t *testing.T) {
	useTempWorkspace(t)
	term, err := startTerminal("client-1", 24, 80)
	if err != nil {
		t.Fatal(err)
	}
	defer term.Close()
	if err := term.WriteInput([]byte("MARKER=hello123\n")); err != nil {
		t.Fatal(err)
	}
	term.WriteInput([]byte("echo state-$MARKER\n"))
	collectOutput(t, term, "state-hello123", 5*time.Second)
}

func TestTerminalCtrlCInterruptsForeground(t *testing.T) {
	useTempWorkspace(t)
	term, err := startTerminal("client-1", 24, 80)
	if err != nil {
		t.Fatal(err)
	}
	defer term.Close()
	// Wait for the shell's first prompt before typing. Until bash switches the
	// tty into raw/readline mode, the kernel line discipline stays in cooked
	// mode; a Ctrl-C (INTR) sent while a not-yet-read line is still queued
	// flushes that pending input instead of signalling a running foreground
	// job. On a slow-starting shell (e.g. macOS bash's one-time "default
	// interactive shell is now zsh" banner) this can otherwise race and
	// silently drop the "sleep 30" command before bash ever reads it.
	collectOutput(t, term, "$ ", 5*time.Second)
	// Two fixes to the naive "sleep 30; echo not-reached" version:
	//  1. Use && rather than ; : ; unconditionally runs the next command
	//     regardless of the previous command's exit status or signal, so
	//     the marker would print even if Ctrl-C correctly interrupted sleep.
	//     && short-circuits on non-zero exit (128+SIGINT here), which is
	//     what this test needs to actually assert "the job was killed".
	//  2. Split the marker across adjacent quotes ('not''reached') so the
	//     *typed* command line - which the pty always echoes back verbatim,
	//     whether or not it ever runs - never itself contains the
	//     contiguous string "notreached". Only genuine output from printf
	//     actually running joins the two halves. Without this split, the
	//     echoed input alone would satisfy a plain Contains check and the
	//     assertion below could never fail even when Ctrl-C does nothing.
	term.WriteInput([]byte("sleep 30 && printf 'not''reached\\n'\n"))
	time.Sleep(500 * time.Millisecond)
	term.WriteInput([]byte{0x03}) // Ctrl-C
	term.WriteInput([]byte("echo after-$?\n"))
	out := collectOutput(t, term, "after-130", 5*time.Second) // 128+SIGINT
	if strings.Contains(out, "notreached") {
		t.Fatal("sleep was not interrupted")
	}
}

func TestTerminalResizeVisibleViaStty(t *testing.T) {
	useTempWorkspace(t)
	term, err := startTerminal("client-1", 24, 80)
	if err != nil {
		t.Fatal(err)
	}
	defer term.Close()
	if err := term.Resize(40, 120); err != nil {
		t.Fatal(err)
	}
	term.WriteInput([]byte("stty size\n"))
	collectOutput(t, term, "40 120", 5*time.Second)
}

func TestTerminalUtf8RoundTrip(t *testing.T) {
	useTempWorkspace(t)
	term, err := startTerminal("client-1", 24, 80)
	if err != nil {
		t.Fatal(err)
	}
	defer term.Close()
	term.WriteInput([]byte("echo 中文输出\n"))
	collectOutput(t, term, "中文输出", 5*time.Second)
}

func TestTerminalExitDeliversExitEvent(t *testing.T) {
	useTempWorkspace(t)
	term, err := startTerminal("client-1", 24, 80)
	if err != nil {
		t.Fatal(err)
	}
	term.WriteInput([]byte("exit 3\n"))
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if exited, code := term.Exited(); exited {
			if code != 3 {
				t.Fatalf("exit code = %d, want 3", code)
			}
			events, _ := term.EventsSince(0)
			last := events[len(events)-1]
			if last.Type != "exit" || last.Data != "3" {
				t.Fatalf("last event = %+v, want exit/3", last)
			}
			if err := term.WriteInput([]byte("x")); err != ErrTerminalExited {
				t.Fatalf("WriteInput after exit = %v, want ErrTerminalExited", err)
			}
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal("terminal never reported exit")
}
