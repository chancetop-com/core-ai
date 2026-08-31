package main

import (
	"testing"
	"time"
)

func TestParseFfmpegVersion(t *testing.T) {
	cases := []struct {
		name      string
		output    string
		want      string
		wantMajor int
	}{
		{"static build", "ffmpeg version n8.0-latest-linux64-gpl-8.0 Copyright (c) 2000-2025\n", "8.0", 8},
		{"debian build", "ffmpeg version 7.1.1-1+deb13u1 Copyright (c) 2000-2025\n", "7.1", 7},
		{"ubuntu build", "ffmpeg version 6.1.1-3ubuntu5 Copyright (c) 2000-2023\n", "6.1", 6},
		{"git build", "ffmpeg version N-119384-g4a134eb14b Copyright (c) 2000-2025\n", "", 0},
		{"empty", "", "", 0},
		{"not ffmpeg", "bash: ffmpeg: command not found\n", "", 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			version, major := parseFfmpegVersion(c.output)
			if version != c.want || major != c.wantMajor {
				t.Fatalf("parseFfmpegVersion() = (%q, %d), want (%q, %d)", version, major, c.want, c.wantMajor)
			}
		})
	}
}

func TestBashTimeout(t *testing.T) {
	original := maxBashTimeout
	defer func() { maxBashTimeout = original }()

	maxBashTimeout = 600_000 * time.Millisecond
	if got := bashTimeout(0); got != 120_000*time.Millisecond {
		t.Fatalf("bashTimeout(0) = %s, want the 2 minute default", got)
	}
	if got := bashTimeout(300_000); got != 300_000*time.Millisecond {
		t.Fatalf("bashTimeout(300000) = %s, want the requested value", got)
	}
	if got := bashTimeout(1_800_000); got != 600_000*time.Millisecond {
		t.Fatalf("bashTimeout(1800000) = %s, want it clamped to the cap", got)
	}

	// a deployment that raises the cap (assembly pods) lets a longer ffmpeg step through
	maxBashTimeout = 1_800_000 * time.Millisecond
	if got := bashTimeout(1_800_000); got != 1_800_000*time.Millisecond {
		t.Fatalf("bashTimeout(1800000) = %s, want the raised cap to apply", got)
	}
	if got := bashTimeout(0); got != 120_000*time.Millisecond {
		t.Fatalf("bashTimeout(0) = %s, want the default to stay put when the cap moves", got)
	}
}
