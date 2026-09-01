package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/creack/pty"
)

const (
	terminalRingBytes = 512 * 1024
	terminalReadChunk = 4096
	closeGracePeriod  = 2 * time.Second
)

var ErrTerminalExited = errors.New("terminal exited")

type Terminal struct {
	id       string
	clientID string

	mu   sync.Mutex
	cond *sync.Cond // broadcast on every ring append / state change

	cmd     *exec.Cmd
	ptyFile *os.File
	ring    *eventRing

	subscribers  int
	disconnectAt time.Time
	exited       bool
	exitCode     int
	closed       bool
}

func startTerminal(clientID string, rows, cols uint16) (*Terminal, error) {
	// --norc/--noprofile keep the prompt deterministic; PS1 comes from env.
	cmd := exec.Command("bash", "--norc", "--noprofile", "-i")
	cmd.Dir = workspaceDir
	cmd.Env = append(minimalEnv(), "TERM=xterm-256color", `PS1=\w $ `)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}

	f, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: rows, Cols: cols})
	if err != nil {
		return nil, err
	}
	idBytes := make([]byte, 16)
	if _, err := rand.Read(idBytes); err != nil {
		f.Close()
		return nil, err
	}
	t := &Terminal{
		id:       hex.EncodeToString(idBytes),
		clientID: clientID,
		cmd:      cmd,
		ptyFile:  f,
		ring:     newEventRing(terminalRingBytes),
	}
	t.cond = sync.NewCond(&t.mu)
	go t.readLoop()
	return t, nil
}

func (t *Terminal) readLoop() {
	buf := make([]byte, terminalReadChunk)
	for {
		n, err := t.ptyFile.Read(buf)
		if n > 0 {
			data := base64.StdEncoding.EncodeToString(buf[:n])
			t.mu.Lock()
			t.ring.append("output", data)
			t.cond.Broadcast()
			t.mu.Unlock()
		}
		if err != nil {
			break // PTY closed: shell exited or Close() ran
		}
	}
	err := t.cmd.Wait()
	code := 0
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		code = exitErr.ExitCode()
	}
	t.mu.Lock()
	t.exited = true
	t.exitCode = code
	t.ring.append("exit", strconv.Itoa(code))
	t.cond.Broadcast()
	t.mu.Unlock()
	// The shell may have exited on its own (e.g. "exit") without Close() ever
	// being called; close the master fd here so it isn't leaked. Close()'s
	// later call to t.ptyFile.Close() then just returns os.ErrClosed, which
	// is already ignored.
	t.ptyFile.Close()
}

func (t *Terminal) ID() string       { return t.id }
func (t *Terminal) ClientID() string { return t.clientID }

func (t *Terminal) WriteInput(b []byte) error {
	t.mu.Lock()
	dead := t.exited || t.closed
	t.mu.Unlock()
	if dead {
		return ErrTerminalExited
	}
	_, err := t.ptyFile.Write(b)
	return err
}

func (t *Terminal) Resize(rows, cols uint16) error {
	t.mu.Lock()
	dead := t.exited || t.closed
	t.mu.Unlock()
	if dead {
		return ErrTerminalExited
	}
	return pty.Setsize(t.ptyFile, &pty.Winsize{Rows: rows, Cols: cols})
}

func (t *Terminal) Close() {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return
	}
	t.closed = true
	pid := t.cmd.Process.Pid
	t.mu.Unlock()

	syscall.Kill(-pid, syscall.SIGTERM) // negative pid: whole process group
	done := make(chan struct{})
	go func() {
		deadline := time.Now().Add(closeGracePeriod)
		for time.Now().Before(deadline) {
			t.mu.Lock()
			exited := t.exited
			t.mu.Unlock()
			if exited {
				break
			}
			time.Sleep(50 * time.Millisecond)
		}
		close(done)
	}()
	<-done
	t.mu.Lock()
	exited := t.exited
	t.mu.Unlock()
	if !exited {
		syscall.Kill(-pid, syscall.SIGKILL)
	}
	t.ptyFile.Close()
}

func (t *Terminal) Exited() (bool, int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.exited, t.exitCode
}

func (t *Terminal) Subscribe() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.subscribers++
	t.disconnectAt = time.Time{}
}

func (t *Terminal) Unsubscribe() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.subscribers--
	if t.subscribers <= 0 {
		t.subscribers = 0
		t.disconnectAt = time.Now()
	}
}

func (t *Terminal) SubscriberCount() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.subscribers
}

func (t *Terminal) DisconnectedSince() (time.Time, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.subscribers > 0 || t.disconnectAt.IsZero() {
		return time.Time{}, false
	}
	return t.disconnectAt, true
}

func (t *Terminal) EventsSince(afterSeq int64) ([]termEvent, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.ring.since(afterSeq)
}

// WaitForEventAfter blocks until an event newer than afterSeq exists, the
// terminal exits, or the timeout elapses. Returns true when new data may exist.
func (t *Terminal) WaitForEventAfter(afterSeq int64, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	timer := time.AfterFunc(timeout, func() {
		t.mu.Lock()
		t.cond.Broadcast()
		t.mu.Unlock()
	})
	defer timer.Stop()
	t.mu.Lock()
	defer t.mu.Unlock()
	for t.ring.lastSeq() <= afterSeq && !t.exited && time.Now().Before(deadline) {
		t.cond.Wait()
	}
	return t.ring.lastSeq() > afterSeq || t.exited
}
