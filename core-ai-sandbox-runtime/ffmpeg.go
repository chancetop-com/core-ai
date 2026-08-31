package main

import (
	"log"
	"os/exec"
	"regexp"
	"strconv"
)

// The assembly cache keys pin an ffmpeg major (DramaAssemblyService.FFMPEG_MAJOR_VERSION) — a
// constant nothing used to verify. The runtime probes the binary once at startup and reports it
// on /health so the server can refuse work from an image whose ffmpeg drifted, instead of
// silently producing differently-encoded products under a cache key that claims otherwise.
var (
	ffmpegVersion string
	ffmpegMajor   int
)

var ffmpegVersionPattern = regexp.MustCompile(`^ffmpeg version n?(\d+)\.(\d+)`)

// parseFfmpegVersion extracts "<major>.<minor>" and the major from the first line of
// `ffmpeg -version`. Both static builds ("ffmpeg version n8.0-latest") and distro builds
// ("ffmpeg version 7.1.1-1ubuntu1") are covered.
func parseFfmpegVersion(output string) (string, int) {
	match := ffmpegVersionPattern.FindStringSubmatch(output)
	if match == nil {
		return "", 0
	}
	major, err := strconv.Atoi(match[1])
	if err != nil {
		return "", 0
	}
	return match[1] + "." + match[2], major
}

// probeFfmpeg is best-effort: an image without ffmpeg stays a perfectly good agent sandbox, it
// just reports no version and the server declines to route assembly jobs to it.
func probeFfmpeg() {
	output, err := exec.Command("ffmpeg", "-version").Output()
	if err != nil {
		log.Printf("ffmpeg not available: %v", err)
		return
	}
	ffmpegVersion, ffmpegMajor = parseFfmpegVersion(string(output))
	if ffmpegMajor == 0 {
		log.Printf("ffmpeg version unparseable: %.80s", string(output))
		return
	}
	log.Printf("ffmpeg version: %s (major %d)", ffmpegVersion, ffmpegMajor)
}
