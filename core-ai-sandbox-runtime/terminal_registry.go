package main

import (
	"errors"
	"sync"
	"time"
)

var ErrTerminalBusy = errors.New("terminal busy")

// TerminalRegistry enforces the one-active-terminal-per-sandbox rule.
type TerminalRegistry struct {
	mu           sync.Mutex
	current      *Terminal
	reclaimAfter time.Duration
}

func NewTerminalRegistry(reclaimAfter time.Duration) *TerminalRegistry {
	return &TerminalRegistry{reclaimAfter: reclaimAfter}
}

// CreateOrRecover holds r.mu for the whole decision + Close + startTerminal +
// install sequence. A single-terminal-per-sandbox registry gains nothing from
// letting creates overlap, and overlapping them is what orphans a terminal:
// two concurrent callers could otherwise both pass the gate, both start a
// real bash+PTY, and have the loser's terminal never installed into r.current
// and never Closed (leaked process group, PTY fd, and readLoop goroutine,
// unreachable via Get/Remove forever). Serializing here costs at most the
// ~2s Close grace period on a takeover, which is acceptable for this
// one-at-a-time resource.
//
// Lock ordering: r.mu is acquired first and Terminal methods (Exited,
// ClientID, SubscriberCount, Close) are called while holding it, which locks
// t.mu inside; Terminal methods never touch the registry, so this is always
// r.mu -> t.mu and never the reverse.
func (r *TerminalRegistry) CreateOrRecover(clientID string, rows, cols uint16) (*Terminal, bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	cur := r.current
	if cur != nil {
		exited, _ := cur.Exited()
		switch {
		case !exited && cur.ClientID() == clientID:
			return cur, true, nil
		case !exited && cur.SubscriberCount() > 0:
			return nil, false, ErrTerminalBusy
		default:
			cur.Close() // takeover or exited leftover: replace with a fresh shell
		}
	}
	t, err := startTerminal(clientID, rows, cols)
	if err != nil {
		return nil, false, err
	}
	r.current = t
	return t, false, nil
}

func (r *TerminalRegistry) Get(id string) *Terminal {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.current != nil && r.current.ID() == id {
		return r.current
	}
	return nil
}

func (r *TerminalRegistry) Remove(id string) {
	r.mu.Lock()
	t := r.current
	if t != nil && t.ID() == id {
		r.current = nil
	} else {
		t = nil
	}
	r.mu.Unlock()
	if t != nil {
		t.Close()
	}
}

// currentForTest exposes the current terminal for test cleanup only.
func (r *TerminalRegistry) currentForTest() *Terminal {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.current
}

func (r *TerminalRegistry) ReclaimDisconnected() int {
	r.mu.Lock()
	t := r.current
	r.mu.Unlock()
	if t == nil {
		return 0
	}
	since, disconnected := t.DisconnectedSince()
	if !disconnected || time.Since(since) < r.reclaimAfter {
		return 0
	}
	r.Remove(t.ID())
	return 1
}
