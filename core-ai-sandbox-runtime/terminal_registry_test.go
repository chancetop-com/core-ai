package main

import (
	"fmt"
	"sync"
	"testing"
	"time"
)

func TestRegistrySameClientRecoversExisting(t *testing.T) {
	useTempWorkspace(t)
	r := NewTerminalRegistry(10 * time.Minute)
	t1, recovered, err := r.CreateOrRecover("c1", 24, 80)
	if err != nil || recovered {
		t.Fatalf("first create: recovered=%v err=%v", recovered, err)
	}
	defer r.Remove(t1.ID())
	t2, recovered, err := r.CreateOrRecover("c1", 24, 80)
	if err != nil || !recovered || t2.ID() != t1.ID() {
		t.Fatalf("same client must recover: recovered=%v ids %s vs %s", recovered, t1.ID(), t2.ID())
	}
}

func TestRegistryDifferentClientBusyWhileSubscribed(t *testing.T) {
	useTempWorkspace(t)
	r := NewTerminalRegistry(10 * time.Minute)
	t1, _, _ := r.CreateOrRecover("c1", 24, 80)
	defer r.Remove(t1.ID())
	t1.Subscribe()
	defer t1.Unsubscribe()
	if _, _, err := r.CreateOrRecover("c2", 24, 80); err != ErrTerminalBusy {
		t.Fatalf("expected ErrTerminalBusy, got %v", err)
	}
}

func TestRegistryDifferentClientTakesOverWhenUnsubscribed(t *testing.T) {
	useTempWorkspace(t)
	r := NewTerminalRegistry(10 * time.Minute)
	t1, _, _ := r.CreateOrRecover("c1", 24, 80)
	t2, recovered, err := r.CreateOrRecover("c2", 24, 80)
	if err != nil || recovered {
		t.Fatalf("takeover: recovered=%v err=%v", recovered, err)
	}
	defer r.Remove(t2.ID())
	if t2.ID() == t1.ID() {
		t.Fatal("takeover must create a fresh terminal")
	}
	if exited, _ := t1.Exited(); !exited {
		// Close is async via signal; allow a moment.
		time.Sleep(500 * time.Millisecond)
		if exited, _ = t1.Exited(); !exited {
			t.Fatal("old terminal must be closed on takeover")
		}
	}
}

func TestRegistryReclaimsAfterDisconnectTimeout(t *testing.T) {
	useTempWorkspace(t)
	r := NewTerminalRegistry(50 * time.Millisecond)
	t1, _, _ := r.CreateOrRecover("c1", 24, 80)
	t1.Subscribe()
	t1.Unsubscribe() // stamps disconnectAt
	time.Sleep(100 * time.Millisecond)
	if n := r.ReclaimDisconnected(); n != 1 {
		t.Fatalf("reclaimed %d, want 1", n)
	}
	if r.Get(t1.ID()) != nil {
		t.Fatal("terminal must be removed after reclaim")
	}
}

func TestRegistryRecoverAfterExitCreatesFresh(t *testing.T) {
	useTempWorkspace(t)
	r := NewTerminalRegistry(10 * time.Minute)
	t1, _, _ := r.CreateOrRecover("c1", 24, 80)
	t1.WriteInput([]byte("exit\n"))
	deadline := time.Now().Add(5 * time.Second)
	for {
		if exited, _ := t1.Exited(); exited {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("shell never exited")
		}
		time.Sleep(50 * time.Millisecond)
	}
	t2, recovered, err := r.CreateOrRecover("c1", 24, 80)
	if err != nil || recovered || t2.ID() == t1.ID() {
		t.Fatalf("restart after exit must create fresh: recovered=%v err=%v", recovered, err)
	}
	r.Remove(t2.ID())
}

// TestRegistryConcurrentCreatesLeaveNoOrphans guards against the race where
// two concurrent CreateOrRecover calls both pass the gate, both start a real
// bash+PTY, and the loser is never installed into r.current and never
// Closed - a leaked process group, PTY fd, and readLoop goroutine that is
// unreachable via Get/Remove forever. CreateOrRecover must hold r.mu across
// the whole decision+start+install sequence so exactly one terminal survives
// per distinct client and every other spawned terminal is actually closed.
func TestRegistryConcurrentCreatesLeaveNoOrphans(t *testing.T) {
	useTempWorkspace(t)
	r := NewTerminalRegistry(10 * time.Minute)

	const n = 8
	var wg sync.WaitGroup
	var mu sync.Mutex
	var created []*Terminal

	for i := 0; i < n; i++ {
		wg.Add(1)
		clientID := fmt.Sprintf("c%d", i)
		go func() {
			defer wg.Done()
			term, _, err := r.CreateOrRecover(clientID, 24, 80)
			if err == ErrTerminalBusy {
				// No subscribers means no caller should observe busy, but
				// tolerate it rather than fail on a benign race outcome.
				return
			}
			if err != nil {
				t.Errorf("CreateOrRecover(%s): %v", clientID, err)
				return
			}
			mu.Lock()
			created = append(created, term)
			mu.Unlock()
		}()
	}
	wg.Wait()

	if len(created) == 0 {
		t.Fatal("no terminal was created")
	}

	// Exactly one of the created terminals must still be reg.Get-able; every
	// other one must be a fully closed orphan, not a leaked live shell.
	survivors := 0
	var currentID string
	for _, term := range created {
		if r.Get(term.ID()) != nil {
			survivors++
			currentID = term.ID()
		}
	}
	if survivors != 1 {
		t.Fatalf("exactly one created terminal must be reg.Get-able, got %d", survivors)
	}

	for _, term := range created {
		if term.ID() == currentID {
			continue
		}
		deadline := time.Now().Add(5 * time.Second)
		for {
			if exited, _ := term.Exited(); exited {
				break
			}
			if time.Now().After(deadline) {
				t.Fatalf("orphaned terminal %s (client %s) never exited", term.ID(), term.ClientID())
			}
			time.Sleep(50 * time.Millisecond)
		}
	}

	r.Remove(currentID)
}
