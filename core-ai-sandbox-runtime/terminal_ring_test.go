package main

import "testing"

func TestRingAppendAssignsSequentialSeqs(t *testing.T) {
	r := newEventRing(1024)
	if seq := r.append("output", "YQ=="); seq != 1 {
		t.Fatalf("first seq = %d, want 1", seq)
	}
	if seq := r.append("output", "Yg=="); seq != 2 {
		t.Fatalf("second seq = %d, want 2", seq)
	}
	if r.lastSeq() != 2 {
		t.Fatalf("lastSeq = %d, want 2", r.lastSeq())
	}
}

func TestRingSinceReturnsEventsAfterSeq(t *testing.T) {
	r := newEventRing(1024)
	r.append("output", "YQ==")
	r.append("output", "Yg==")
	r.append("exit", "0")
	events, overflowed := r.since(1)
	if overflowed {
		t.Fatal("unexpected overflow")
	}
	if len(events) != 2 || events[0].Data != "Yg==" || events[1].Type != "exit" {
		t.Fatalf("unexpected events: %+v", events)
	}
}

func TestRingEvictsOldestWhenOverByteBudget(t *testing.T) {
	r := newEventRing(10) // tiny budget: each 6-byte payload evicts predecessors
	r.append("output", "AAAAAA")
	r.append("output", "BBBBBB")
	events, overflowed := r.since(0) // ask from the beginning
	if !overflowed {
		t.Fatal("expected overflow after eviction")
	}
	if len(events) != 1 || events[0].Data != "BBBBBB" {
		t.Fatalf("unexpected retained events: %+v", events)
	}
}

func TestRingSinceUpToDateIsEmptyNoOverflow(t *testing.T) {
	r := newEventRing(10)
	r.append("output", "AAAAAA")
	r.append("output", "BBBBBB") // evicts first
	events, overflowed := r.since(2)
	if overflowed || len(events) != 0 {
		t.Fatalf("caught-up reader must get nothing: events=%v overflowed=%v", events, overflowed)
	}
}
