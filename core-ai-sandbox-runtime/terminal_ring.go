package main

// termEvent is one SSE-visible terminal event. Data is already Base64 for
// output events; other types carry small ASCII payloads.
type termEvent struct {
	Seq  int64
	Type string
	Data string
}

// eventRing keeps the most recent events within a total payload byte budget.
// Not goroutine-safe: callers must hold the owning Terminal's mutex.
type eventRing struct {
	maxBytes int
	bytes    int
	events   []termEvent
	nextSeq  int64
	// evictedThrough is the highest seq that has been dropped; since() uses it
	// to detect that a reader's cursor points into discarded history.
	evictedThrough int64
}

func newEventRing(maxBytes int) *eventRing {
	return &eventRing{maxBytes: maxBytes, nextSeq: 1}
}

func (r *eventRing) append(evType, data string) int64 {
	seq := r.nextSeq
	r.nextSeq++
	r.events = append(r.events, termEvent{Seq: seq, Type: evType, Data: data})
	r.bytes += len(data)
	for r.bytes > r.maxBytes && len(r.events) > 1 {
		r.bytes -= len(r.events[0].Data)
		r.evictedThrough = r.events[0].Seq
		r.events = r.events[1:]
	}
	return seq
}

func (r *eventRing) since(afterSeq int64) ([]termEvent, bool) {
	overflowed := afterSeq < r.evictedThrough
	var out []termEvent
	for _, e := range r.events {
		if e.Seq > afterSeq {
			out = append(out, e)
		}
	}
	return out, overflowed
}

func (r *eventRing) lastSeq() int64 { return r.nextSeq - 1 }
