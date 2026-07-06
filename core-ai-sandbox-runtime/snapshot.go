package main

import (
	"archive/tar"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Caps are vars (not consts) so tests can lower them.
var (
	maxSnapshotFiles      = 10000
	maxSnapshotFileSize   = int64(200 << 20) // skip a single file larger than 200 MB
	maxSnapshotTotalSize  = int64(2 << 30)   // reject capture when uncompressed total exceeds 2 GB
	maxRestoreArchiveSize = int64(500 << 20) // reject restore body larger than 500 MB compressed
)

const snapshotManifestName = "manifest.json"
const snapshotFormat = "core-ai-sandbox-snapshot/v1"

type snapshotRoot struct {
	Name          string `json:"name"`
	Target        string `json:"target"`
	ArchivePrefix string `json:"archive_prefix"`
}

type snapshotManifest struct {
	Format         string         `json:"format"`
	CreatedAt      string         `json:"created_at"`
	RuntimeVersion string         `json:"runtime_version"`
	FileCount      int            `json:"file_count"`
	Roots          []snapshotRoot `json:"roots"`
}

type snapshotError struct {
	Status string `json:"status"`
	Error  string `json:"error"`
}

// liveSnapshotRoots returns the whitelist of directories captured and restored.
// /workspace is included since 2026-07-06: the runtime creates it at startup and
// it is the default cwd for bash/python tools, so agent files land there.
func liveSnapshotRoots() []snapshotRoot {
	return []snapshotRoot{
		{Name: "tmp", Target: os.TempDir(), ArchivePrefix: "roots/tmp"},
		{Name: "skill", Target: skillBaseDir, ArchivePrefix: "roots/skill"},
		{Name: "workspace", Target: workspaceDir, ArchivePrefix: "roots/workspace"},
	}
}

var snapshotExcludedSegments = map[string]bool{
	".X11-unix":   true,
	".ICE-unix":   true,
	"__pycache__": true,
}

func isSnapshotExcluded(relPath string) bool {
	if strings.HasSuffix(relPath, ".sock") {
		return true
	}
	if strings.HasPrefix(filepath.Base(relPath), "core-ai-snapshot-") {
		return true
	}
	for _, seg := range strings.Split(relPath, string(os.PathSeparator)) {
		if snapshotExcludedSegments[seg] {
			return true
		}
	}
	return false
}

type snapshotEntry struct {
	absPath     string
	archiveName string
	info        os.FileInfo
}

// collectSnapshotEntries walks the roots and returns regular files to pack.
// Symlinks, sockets, devices and excluded paths are skipped; oversized single
// files are skipped with a log line; total caps abort the capture.
func collectSnapshotEntries(roots []snapshotRoot) ([]snapshotEntry, error) {
	var entries []snapshotEntry
	var totalSize int64
	for _, root := range roots {
		if _, err := os.Stat(root.Target); os.IsNotExist(err) {
			continue
		}
		err := filepath.Walk(root.Target, func(path string, info os.FileInfo, err error) error {
			if err != nil {
				log.Printf("snapshot walk skip: path=%s err=%v", path, err)
				return nil
			}
			rel, relErr := filepath.Rel(root.Target, path)
			if relErr != nil || rel == "." {
				return nil
			}
			if isSnapshotExcluded(rel) {
				if info.IsDir() {
					return filepath.SkipDir
				}
				return nil
			}
			if info.IsDir() {
				return nil
			}
			if !info.Mode().IsRegular() {
				return nil // symlinks, sockets, devices, fifos are never captured
			}
			if info.Size() > maxSnapshotFileSize {
				log.Printf("snapshot skip oversized file: path=%s size=%d", path, info.Size())
				return nil
			}
			totalSize += info.Size()
			if totalSize > maxSnapshotTotalSize {
				return fmt.Errorf("snapshot exceeds max total size (%d bytes)", maxSnapshotTotalSize)
			}
			entries = append(entries, snapshotEntry{absPath: path, archiveName: root.ArchivePrefix + "/" + filepath.ToSlash(rel), info: info})
			if len(entries) > maxSnapshotFiles {
				return fmt.Errorf("snapshot exceeds max file count (%d)", maxSnapshotFiles)
			}
			return nil
		})
		if err != nil {
			return nil, err
		}
	}
	return entries, nil
}

// buildSnapshotArchive writes manifest.json plus all whitelisted files as tar.gz.
// Returns the number of packed files.
func buildSnapshotArchive(w io.Writer, roots []snapshotRoot) (int, error) {
	entries, err := collectSnapshotEntries(roots)
	if err != nil {
		return 0, err
	}
	gw := gzip.NewWriter(w)
	tw := tar.NewWriter(gw)

	manifest := snapshotManifest{
		Format:         snapshotFormat,
		CreatedAt:      time.Now().UTC().Format(time.RFC3339),
		RuntimeVersion: runtimeVersion,
		FileCount:      len(entries),
		Roots:          roots,
	}
	manifestBytes, err := json.Marshal(manifest)
	if err != nil {
		return 0, err
	}
	if err := tw.WriteHeader(&tar.Header{Name: snapshotManifestName, Mode: 0644, Size: int64(len(manifestBytes)), Typeflag: tar.TypeReg}); err != nil {
		return 0, err
	}
	if _, err := tw.Write(manifestBytes); err != nil {
		return 0, err
	}

	for _, e := range entries {
		hdr := &tar.Header{
			Name:     e.archiveName,
			Mode:     int64(e.info.Mode().Perm()),
			Size:     e.info.Size(),
			ModTime:  e.info.ModTime(),
			Typeflag: tar.TypeReg,
		}
		if err := tw.WriteHeader(hdr); err != nil {
			return 0, err
		}
		f, err := os.Open(e.absPath)
		if err != nil {
			return 0, err
		}
		// CopyN (not Copy) so a file growing mid-walk cannot corrupt the tar stream.
		if _, err := io.CopyN(tw, f, e.info.Size()); err != nil {
			f.Close()
			return 0, fmt.Errorf("pack %s: %w", e.absPath, err)
		}
		f.Close()
	}
	if err := tw.Close(); err != nil {
		return 0, err
	}
	if err := gw.Close(); err != nil {
		return 0, err
	}
	return len(entries), nil
}

// restoreSnapshotArchive extracts a snapshot produced by buildSnapshotArchive.
// Entry targets are resolved from OUR root whitelist by archive prefix — the
// manifest's target paths are never trusted. Escaping entries fail the restore;
// symlink entries are skipped. Existing files are overwritten (overlay mode).
func restoreSnapshotArchive(r io.Reader, roots []snapshotRoot) (int, error) {
	gz, err := gzip.NewReader(r)
	if err != nil {
		return 0, fmt.Errorf("invalid gzip: %w", err)
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	restored := 0
	// Running total of uncompressed bytes written, capped by maxSnapshotTotalSize
	// so a hostile sha-valid archive cannot decompress far past the body cap
	// and fill the sandbox disk (tar-bomb DoS).
	var totalWritten int64
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return restored, fmt.Errorf("invalid tar: %w", err)
		}
		name := filepath.ToSlash(hdr.Name)
		if name == snapshotManifestName {
			var manifest snapshotManifest
			if err := json.NewDecoder(io.LimitReader(tr, 1<<20)).Decode(&manifest); err != nil {
				return restored, fmt.Errorf("invalid manifest: %w", err)
			}
			if !strings.HasPrefix(manifest.Format, "core-ai-sandbox-snapshot/") {
				return restored, fmt.Errorf("unsupported snapshot format: %s", manifest.Format)
			}
			continue
		}
		if hdr.Typeflag == tar.TypeSymlink || hdr.Typeflag == tar.TypeLink {
			log.Printf("snapshot restore skip link entry: %s", name)
			continue
		}
		var target string
		for _, root := range roots {
			prefix := root.ArchivePrefix + "/"
			if strings.HasPrefix(name, prefix) {
				target = filepath.Join(root.Target, filepath.FromSlash(strings.TrimPrefix(name, prefix)))
				if !isPathWithin(filepath.Clean(target), filepath.Clean(root.Target)) {
					return restored, fmt.Errorf("entry escapes root: %s", name)
				}
				break
			}
		}
		if target == "" {
			return restored, fmt.Errorf("entry outside whitelisted roots: %s", name)
		}
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, os.FileMode(hdr.Mode).Perm()|0700); err != nil {
				return restored, err
			}
		case tar.TypeReg:
			if restored >= maxSnapshotFiles {
				return restored, fmt.Errorf("archive exceeds max file count (%d)", maxSnapshotFiles)
			}
			if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
				return restored, err
			}
			f, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(hdr.Mode).Perm())
			if err != nil {
				return restored, err
			}
			// Copy through a limiter so one extra byte past the remaining
			// budget is detectable without trusting the tar header size.
			remaining := maxSnapshotTotalSize - totalWritten
			written, err := io.Copy(f, io.LimitReader(tr, remaining+1))
			if err != nil {
				f.Close()
				return restored, err
			}
			f.Close()
			if written > remaining {
				return restored, fmt.Errorf("archive exceeds max uncompressed size (%d bytes)", maxSnapshotTotalSize)
			}
			totalWritten += written
			restored++
		default:
			log.Printf("snapshot restore skip entry type %d: %s", hdr.Typeflag, name)
		}
	}
	return restored, nil
}

func writeSnapshotError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(snapshotError{Status: "error", Error: msg})
}

func handleSnapshot(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	roots := liveSnapshotRoots()
	// Pre-count so cap violations become a clean 413 instead of a truncated 200 stream.
	entries, err := collectSnapshotEntries(roots)
	if err != nil {
		writeSnapshotError(w, http.StatusRequestEntityTooLarge, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/gzip")
	w.Header().Set("X-Snapshot-File-Count", strconv.Itoa(len(entries)))
	w.Header().Set("X-Snapshot-Runtime-Version", runtimeVersion)
	count, err := buildSnapshotArchive(w, roots)
	if err != nil {
		// Headers already sent; the truncated stream will fail the server-side gzip/sha check.
		log.Printf("snapshot capture failed mid-stream: %v", err)
		// Abort the connection so the server side sees a stream error instead of
		// a clean EOF — a truncated archive must never become a valid snapshot.
		panic(http.ErrAbortHandler)
	}
	log.Printf("snapshot captured: files=%d", count)
}

func handleSnapshotRestore(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	expectedSha := strings.ToLower(strings.TrimSpace(r.Header.Get("X-Snapshot-Sha256")))
	if expectedSha == "" {
		writeSnapshotError(w, http.StatusBadRequest, "X-Snapshot-Sha256 header is required")
		return
	}
	tmp, err := os.CreateTemp(os.TempDir(), "core-ai-snapshot-*.tmp")
	if err != nil {
		writeSnapshotError(w, http.StatusInternalServerError, "failed to create staging file: "+err.Error())
		return
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()

	hasher := sha256.New()
	n, err := io.Copy(io.MultiWriter(tmp, hasher), io.LimitReader(r.Body, maxRestoreArchiveSize+1))
	if err != nil {
		writeSnapshotError(w, http.StatusBadRequest, "failed to read body: "+err.Error())
		return
	}
	if n > maxRestoreArchiveSize {
		writeSnapshotError(w, http.StatusRequestEntityTooLarge, fmt.Sprintf("archive exceeds max size (%d bytes)", maxRestoreArchiveSize))
		return
	}
	actualSha := hex.EncodeToString(hasher.Sum(nil))
	if actualSha != expectedSha {
		writeSnapshotError(w, http.StatusBadRequest, "sha256 mismatch")
		return
	}
	if _, err := tmp.Seek(0, io.SeekStart); err != nil {
		writeSnapshotError(w, http.StatusInternalServerError, err.Error())
		return
	}
	restored, err := restoreSnapshotArchive(tmp, liveSnapshotRoots())
	if err != nil {
		writeSnapshotError(w, http.StatusBadRequest, "restore failed: "+err.Error())
		return
	}
	log.Printf("snapshot restored: files=%d, size=%d", restored, n)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"status": "ok", "files": restored})
}
