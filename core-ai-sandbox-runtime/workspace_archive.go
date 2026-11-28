package main

import (
	"archive/tar"
	"compress/gzip"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	workspaceArchiveFormat          = "core-ai-workspace-archive/v1"
	workspaceArchiveManifestName    = "manifest.json"
	workspaceArchivePrefix          = "workspace/"
	workspaceRestoreStagingRel      = ".core-ai/restore-staging"
	workspaceRestoreBackupPrefix    = "restore-backup-"
	workspaceArchiveCopyBufferBytes = 64 * 1024
)

var workspaceTaskOutputMaxAge = 7 * 24 * time.Hour

type workspaceArchiveRequest struct {
	ArchiveID   string `json:"archiveId"`
	WorkspaceID string `json:"workspaceId"`
	Generation  int64  `json:"generation"`
	Image       string `json:"image"`
}

type workspaceArchiveManifest struct {
	Format           string `json:"format"`
	ArchiveID        string `json:"archiveId,omitempty"`
	WorkspaceID      string `json:"workspaceId"`
	VolumeGeneration int64  `json:"volumeGeneration"`
	Image            string `json:"image"`
	CreatedAt        string `json:"createdAt"`
	FileCount        int64  `json:"fileCount"`
	SizeBytes        int64  `json:"sizeBytes"`
}

type workspaceRestoreResponse struct {
	Status           string `json:"status"`
	WorkspaceID      string `json:"workspaceId"`
	VolumeGeneration int64  `json:"volumeGeneration"`
	FileCount        int64  `json:"fileCount"`
	BytesRestored    int64  `json:"bytesRestored"`
}

type workspaceArchiveError struct {
	Status    string `json:"status"`
	ErrorCode string `json:"errorCode"`
	Error     string `json:"error"`
}

var (
	errArchiveManifestMissing    = errors.New("workspace archive manifest.json missing")
	errArchiveInvalidFormat      = errors.New("unsupported workspace archive format")
	errArchiveGenerationMismatch = errors.New("workspace archive generation mismatch")
	errArchiveWorkspaceMismatch  = errors.New("workspace archive workspaceId mismatch")
)

func writeWorkspaceArchiveError(w http.ResponseWriter, status int, errorCode, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(workspaceArchiveError{
		Status:    "error",
		ErrorCode: errorCode,
		Error:     msg,
	})
}

func isPersistentWorkspaceEnabled() bool {
	return strings.TrimSpace(os.Getenv("WORKSPACE_ENV_FILE")) != ""
}

func checkWorkspaceQuiescent() (string, bool) {
	if taskRegistry != nil && taskRegistry.HasRunning() {
		return "workspace has active async tasks", false
	}
	if activeTerminalRegistry != nil && activeTerminalRegistry.HasActive() {
		return "workspace has an active terminal session", false
	}
	return "", true
}

func isWorkspaceArchiveExcluded(relSlash string, info fs.FileInfo, now time.Time) bool {
	if relSlash == workspaceRestoreStagingRel || strings.HasPrefix(relSlash, workspaceRestoreStagingRel+"/") {
		return true
	}
	if strings.HasPrefix(relSlash, ".core-ai/"+workspaceRestoreBackupPrefix) {
		return true
	}
	if strings.HasSuffix(relSlash, ".sock") {
		return true
	}
	if info != nil && !info.IsDir() && strings.HasPrefix(relSlash, ".core-ai/tasks/") && strings.HasSuffix(relSlash, ".out") {
		if workspaceTaskOutputMaxAge > 0 && now.Sub(info.ModTime()) > workspaceTaskOutputMaxAge {
			return true
		}
	}
	return false
}

func measureWorkspaceArchive(root string, now time.Time) (int64, int64, error) {
	var fileCount int64
	var sizeBytes int64
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			log.Printf("workspace archive measure skip: path=%s err=%v", path, walkErr)
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil || rel == "." {
			return nil
		}
		relSlash := filepath.ToSlash(rel)
		if d.IsDir() {
			if isWorkspaceArchiveExcluded(relSlash, nil, now) {
				return filepath.SkipDir
			}
			return nil
		}
		info, err := d.Info()
		if err != nil {
			log.Printf("workspace archive stat skip: path=%s err=%v", path, err)
			return nil
		}
		if isWorkspaceArchiveExcluded(relSlash, info, now) {
			return nil
		}
		mode := info.Mode()
		if mode.IsRegular() {
			fileCount++
			sizeBytes += info.Size()
		} else if mode&os.ModeSymlink != 0 {
			if safeWorkspaceSymlinkTarget(root, path) {
				fileCount++
			}
		}
		return nil
	})
	return fileCount, sizeBytes, err
}

func safeWorkspaceSymlinkTarget(root, linkPath string) bool {
	target, err := os.Readlink(linkPath)
	if err != nil {
		return false
	}
	var resolved string
	if filepath.IsAbs(target) {
		resolved = filepath.Clean(target)
	} else {
		resolved = filepath.Clean(filepath.Join(filepath.Dir(linkPath), target))
	}
	return isPathWithin(resolved, filepath.Clean(root))
}

func streamWorkspaceArchive(w io.Writer, root string, manifest workspaceArchiveManifest, now time.Time) error {
	gw := gzip.NewWriter(w)
	tw := tar.NewWriter(gw)

	manifestBytes, err := json.Marshal(manifest)
	if err != nil {
		return err
	}
	if err := tw.WriteHeader(&tar.Header{
		Name:     workspaceArchiveManifestName,
		Mode:     0644,
		Size:     int64(len(manifestBytes)),
		ModTime:  now,
		Typeflag: tar.TypeReg,
	}); err != nil {
		return err
	}
	if _, err := tw.Write(manifestBytes); err != nil {
		return err
	}

	copyBuf := make([]byte, workspaceArchiveCopyBufferBytes)
	err = filepath.WalkDir(root, func(path string, d fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			log.Printf("workspace archive walk skip: path=%s err=%v", path, walkErr)
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil || rel == "." {
			return nil
		}
		relSlash := filepath.ToSlash(rel)
		if d.IsDir() {
			if isWorkspaceArchiveExcluded(relSlash, nil, now) {
				return filepath.SkipDir
			}
			info, err := d.Info()
			if err != nil {
				return nil
			}
			return tw.WriteHeader(&tar.Header{
				Name:     workspaceArchivePrefix + relSlash + "/",
				Mode:     int64(info.Mode().Perm()),
				ModTime:  info.ModTime(),
				Typeflag: tar.TypeDir,
			})
		}

		info, err := d.Info()
		if err != nil {
			log.Printf("workspace archive info skip: path=%s err=%v", path, err)
			return nil
		}
		if isWorkspaceArchiveExcluded(relSlash, info, now) {
			return nil
		}
		mode := info.Mode()
		if mode&os.ModeSymlink != 0 {
			if !safeWorkspaceSymlinkTarget(root, path) {
				log.Printf("workspace archive skip escaping symlink: %s", relSlash)
				return nil
			}
			linkTarget, err := os.Readlink(path)
			if err != nil {
				return nil
			}
			return tw.WriteHeader(&tar.Header{
				Name:     workspaceArchivePrefix + relSlash,
				Linkname: linkTarget,
				Mode:     int64(mode.Perm()),
				ModTime:  info.ModTime(),
				Typeflag: tar.TypeSymlink,
			})
		}
		if !mode.IsRegular() {
			return nil
		}

		size := info.Size()
		if err := tw.WriteHeader(&tar.Header{
			Name:     workspaceArchivePrefix + relSlash,
			Mode:     int64(mode.Perm()),
			Size:     size,
			ModTime:  info.ModTime(),
			Typeflag: tar.TypeReg,
		}); err != nil {
			return err
		}
		f, err := os.Open(path)
		if err != nil {
			return fmt.Errorf("open %s: %w", relSlash, err)
		}
		written, copyErr := io.CopyBuffer(tw, io.LimitReader(f, size), copyBuf)
		closeErr := f.Close()
		if copyErr != nil {
			return fmt.Errorf("pack %s: %w", relSlash, copyErr)
		}
		if closeErr != nil {
			return fmt.Errorf("close %s: %w", relSlash, closeErr)
		}
		if written != size {
			return fmt.Errorf("file size changed while archiving %s: expected %d wrote %d", relSlash, size, written)
		}
		return nil
	})
	if err != nil {
		return err
	}
	if err := tw.Close(); err != nil {
		return err
	}
	return gw.Close()
}

func handleWorkspaceArchive(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !isPersistentWorkspaceEnabled() {
		writeWorkspaceArchiveError(w, http.StatusConflict, "WORKSPACE_UNSUPPORTED", "persistent workspace is not enabled")
		return
	}
	if reason, ok := checkWorkspaceQuiescent(); !ok {
		writeWorkspaceArchiveError(w, http.StatusConflict, "WORKSPACE_BUSY", reason)
		return
	}

	var req workspaceArchiveRequest
	if r.Body != nil {
		dec := json.NewDecoder(io.LimitReader(r.Body, 64*1024))
		if err := dec.Decode(&req); err != nil && !errors.Is(err, io.EOF) {
			writeWorkspaceArchiveError(w, http.StatusBadRequest, "WORKSPACE_INVALID_REQUEST", "invalid archive request JSON: "+err.Error())
			return
		}
	}
	if req.Generation < 0 {
		writeWorkspaceArchiveError(w, http.StatusBadRequest, "WORKSPACE_INVALID_REQUEST", "generation must be >= 0")
		return
	}

	workspaceID := strings.TrimSpace(req.WorkspaceID)
	if workspaceID == "" {
		workspaceID = strings.TrimSpace(os.Getenv("WORKSPACE_ID"))
	}
	image := strings.TrimSpace(req.Image)
	if image == "" {
		image = strings.TrimSpace(os.Getenv("WORKSPACE_IMAGE"))
	}

	info, err := os.Stat(workspaceDir)
	if err != nil || !info.IsDir() {
		writeWorkspaceArchiveError(w, http.StatusInternalServerError, "WORKSPACE_INTERNAL", "workspace directory is unavailable")
		return
	}

	now := time.Now().UTC()
	fileCount, sizeBytes, err := measureWorkspaceArchive(workspaceDir, now)
	if err != nil {
		writeWorkspaceArchiveError(w, http.StatusInternalServerError, "WORKSPACE_INTERNAL", "failed to inspect workspace: "+err.Error())
		return
	}

	manifest := workspaceArchiveManifest{
		Format:           workspaceArchiveFormat,
		ArchiveID:        req.ArchiveID,
		WorkspaceID:      workspaceID,
		VolumeGeneration: req.Generation,
		Image:            image,
		CreatedAt:        now.Format(time.RFC3339),
		FileCount:        fileCount,
		SizeBytes:        sizeBytes,
	}

	w.Header().Set("Content-Type", "application/gzip")
	w.Header().Set("X-Archive-File-Count", strconv.FormatInt(fileCount, 10))
	w.Header().Set("X-Archive-Estimated-Bytes", strconv.FormatInt(sizeBytes, 10))
	w.Header().Set("X-Archive-Generation", strconv.FormatInt(req.Generation, 10))
	if workspaceID != "" {
		w.Header().Set("X-Archive-Workspace-Id", workspaceID)
	}
	w.WriteHeader(http.StatusOK)

	if err := streamWorkspaceArchive(w, workspaceDir, manifest, now); err != nil {
		log.Printf("workspace archive stream error: archiveId=%s workspaceId=%s err=%v", req.ArchiveID, workspaceID, err)
	}
}

func workspaceHasUserContent(root string) (bool, error) {
	hasContent := false
	errStop := errors.New("found user content")
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil || rel == "." {
			return nil
		}
		relSlash := filepath.ToSlash(rel)
		if relSlash == workspaceRestoreStagingRel || strings.HasPrefix(relSlash, workspaceRestoreStagingRel+"/") {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasPrefix(relSlash, ".core-ai/"+workspaceRestoreBackupPrefix) {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if !d.IsDir() {
			hasContent = true
			return errStop
		}
		if isPlatformScaffoldDir(relSlash) {
			return nil
		}
		hasContent = true
		return errStop
	})
	if errors.Is(err, errStop) {
		return true, nil
	}
	return hasContent, err
}

func isPlatformScaffoldDir(relSlash string) bool {
	switch relSlash {
	case ".core-ai", ".core-ai/logs", ".core-ai/tasks", "home", ".npm-global", ".npm-global/bin":
		return true
	default:
		return false
	}
}

func randomRestoreID() string {
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 16)
	}
	return hex.EncodeToString(buf)
}

func extractWorkspaceArchiveToStaging(r io.Reader, stagingDir string, expectedGen int64, expectedWorkspaceID string) (workspaceArchiveManifest, int64, int64, error) {
	var manifest workspaceArchiveManifest
	gz, err := gzip.NewReader(r)
	if err != nil {
		return manifest, 0, 0, fmt.Errorf("invalid gzip stream: %w", err)
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	firstHdr, err := tr.Next()
	if err != nil {
		return manifest, 0, 0, errArchiveManifestMissing
	}
	if filepath.ToSlash(firstHdr.Name) != workspaceArchiveManifestName {
		return manifest, 0, 0, errArchiveManifestMissing
	}
	if err := json.NewDecoder(io.LimitReader(tr, 1<<20)).Decode(&manifest); err != nil {
		return manifest, 0, 0, fmt.Errorf("invalid manifest: %w", err)
	}
	if manifest.Format != workspaceArchiveFormat {
		return manifest, 0, 0, fmt.Errorf("%w: %s", errArchiveInvalidFormat, manifest.Format)
	}
	if manifest.VolumeGeneration != expectedGen {
		return manifest, 0, 0, fmt.Errorf("%w: manifest=%d expected=%d", errArchiveGenerationMismatch, manifest.VolumeGeneration, expectedGen)
	}
	if expectedWorkspaceID != "" && manifest.WorkspaceID != expectedWorkspaceID {
		return manifest, 0, 0, fmt.Errorf("%w: manifest=%q expected=%q", errArchiveWorkspaceMismatch, manifest.WorkspaceID, expectedWorkspaceID)
	}

	if err := os.MkdirAll(stagingDir, 0755); err != nil {
		return manifest, 0, 0, err
	}
	cleanStaging := filepath.Clean(stagingDir)
	copyBuf := make([]byte, workspaceArchiveCopyBufferBytes)
	var restoredFiles int64
	var restoredBytes int64

	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return manifest, restoredFiles, restoredBytes, fmt.Errorf("invalid tar stream: %w", err)
		}
		name := filepath.ToSlash(hdr.Name)
		if !strings.HasPrefix(name, workspaceArchivePrefix) {
			return manifest, restoredFiles, restoredBytes, fmt.Errorf("archive entry outside workspace root: %s", name)
		}
		rel := strings.TrimPrefix(name, workspaceArchivePrefix)
		rel = strings.TrimSuffix(rel, "/")
		if rel == "" || rel == "." {
			continue
		}
		if rel == workspaceRestoreStagingRel || strings.HasPrefix(rel, workspaceRestoreStagingRel+"/") {
			continue
		}
		target := filepath.Join(cleanStaging, filepath.FromSlash(rel))
		if !isPathWithin(filepath.Clean(target), cleanStaging) {
			return manifest, restoredFiles, restoredBytes, fmt.Errorf("archive entry escapes staging root: %s", name)
		}

		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, os.FileMode(hdr.Mode).Perm()|0700); err != nil {
				return manifest, restoredFiles, restoredBytes, err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
				return manifest, restoredFiles, restoredBytes, err
			}
			mode := os.FileMode(hdr.Mode).Perm()
			if mode == 0 {
				mode = 0644
			}
			f, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
			if err != nil {
				return manifest, restoredFiles, restoredBytes, err
			}
			n, copyErr := io.CopyBuffer(f, tr, copyBuf)
			closeErr := f.Close()
			if copyErr != nil {
				return manifest, restoredFiles, restoredBytes, copyErr
			}
			if closeErr != nil {
				return manifest, restoredFiles, restoredBytes, closeErr
			}
			restoredFiles++
			restoredBytes += n
		case tar.TypeSymlink:
			linkTarget := hdr.Linkname
			var resolved string
			if filepath.IsAbs(linkTarget) {
				resolved = filepath.Clean(linkTarget)
				if !isPathWithin(resolved, filepath.Clean(workspaceDir)) {
					return manifest, restoredFiles, restoredBytes, fmt.Errorf("symlink escapes workspace root: %s -> %s", name, linkTarget)
				}
			} else {
				resolved = filepath.Clean(filepath.Join(filepath.Dir(target), linkTarget))
				if !isPathWithin(resolved, cleanStaging) {
					return manifest, restoredFiles, restoredBytes, fmt.Errorf("symlink escapes staging root: %s -> %s", name, linkTarget)
				}
			}
			if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
				return manifest, restoredFiles, restoredBytes, err
			}
			_ = os.Remove(target)
			if err := os.Symlink(linkTarget, target); err != nil {
				return manifest, restoredFiles, restoredBytes, err
			}
			restoredFiles++
		default:
			log.Printf("workspace restore skip entry type %d: %s", hdr.Typeflag, name)
		}
	}

	return manifest, restoredFiles, restoredBytes, nil
}

func promoteRestoredWorkspace(root, stagingDir, runID string) error {
	coreAiDir := filepath.Join(root, ".core-ai")
	backupDir := filepath.Join(coreAiDir, workspaceRestoreBackupPrefix+runID)
	if err := os.MkdirAll(backupDir, 0755); err != nil {
		return err
	}

	type movedPair struct {
		from string
		to   string
	}
	var backedUp []movedPair
	var promoted []string

	rollback := func() {
		for _, p := range promoted {
			_ = os.RemoveAll(p)
		}
		for i := len(backedUp) - 1; i >= 0; i-- {
			_ = os.Rename(backedUp[i].to, backedUp[i].from)
		}
		_ = os.RemoveAll(backupDir)
	}

	// 1. Move existing top-level entries (except .core-ai) into backupDir.
	existingTop, err := os.ReadDir(root)
	if err != nil {
		_ = os.RemoveAll(backupDir)
		return err
	}
	for _, entry := range existingTop {
		if entry.Name() == ".core-ai" {
			continue
		}
		src := filepath.Join(root, entry.Name())
		dst := filepath.Join(backupDir, entry.Name())
		if err := os.Rename(src, dst); err != nil {
			rollback()
			return fmt.Errorf("backup existing entry %s: %w", entry.Name(), err)
		}
		backedUp = append(backedUp, movedPair{from: src, to: dst})
	}

	// 2. Move existing .core-ai entries (except restore-staging and restore-backup-*) into backupDir/.core-ai.
	backupCoreAi := filepath.Join(backupDir, ".core-ai")
	if err := os.MkdirAll(backupCoreAi, 0755); err != nil {
		rollback()
		return err
	}
	if existingCoreAi, err := os.ReadDir(coreAiDir); err == nil {
		for _, entry := range existingCoreAi {
			name := entry.Name()
			if name == "restore-staging" || strings.HasPrefix(name, workspaceRestoreBackupPrefix) {
				continue
			}
			src := filepath.Join(coreAiDir, name)
			dst := filepath.Join(backupCoreAi, name)
			if err := os.Rename(src, dst); err != nil {
				rollback()
				return fmt.Errorf("backup .core-ai/%s: %w", name, err)
			}
			backedUp = append(backedUp, movedPair{from: src, to: dst})
		}
	}

	// 3. Move staged top-level entries (except .core-ai) into root.
	stagedTop, err := os.ReadDir(stagingDir)
	if err != nil {
		rollback()
		return err
	}
	for _, entry := range stagedTop {
		if entry.Name() == ".core-ai" {
			continue
		}
		src := filepath.Join(stagingDir, entry.Name())
		dst := filepath.Join(root, entry.Name())
		if err := os.Rename(src, dst); err != nil {
			rollback()
			return fmt.Errorf("promote entry %s: %w", entry.Name(), err)
		}
		promoted = append(promoted, dst)
	}

	// 4. Move staged .core-ai entries into root/.core-ai.
	stagedCoreAi := filepath.Join(stagingDir, ".core-ai")
	if entries, err := os.ReadDir(stagedCoreAi); err == nil {
		for _, entry := range entries {
			name := entry.Name()
			if name == "restore-staging" || strings.HasPrefix(name, workspaceRestoreBackupPrefix) {
				continue
			}
			src := filepath.Join(stagedCoreAi, name)
			dst := filepath.Join(coreAiDir, name)
			if err := os.Rename(src, dst); err != nil {
				rollback()
				return fmt.Errorf("promote .core-ai/%s: %w", name, err)
			}
			promoted = append(promoted, dst)
		}
	}

	// Clean up backup directory after successful swap.
	if err := os.RemoveAll(backupDir); err != nil {
		log.Printf("warning: failed to remove restore backup dir %s: %v", backupDir, err)
	}
	return nil
}

func invalidateWorkspaceEnvCache() {
	envCache.mu.Lock()
	envCache.loaded = false
	envCache.values = nil
	envCache.warnings = nil
	envCache.mu.Unlock()
}

func handleWorkspaceRestore(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !isPersistentWorkspaceEnabled() {
		writeWorkspaceArchiveError(w, http.StatusConflict, "WORKSPACE_UNSUPPORTED", "persistent workspace is not enabled")
		return
	}
	if reason, ok := checkWorkspaceQuiescent(); !ok {
		writeWorkspaceArchiveError(w, http.StatusConflict, "WORKSPACE_BUSY", reason)
		return
	}

	genHeader := strings.TrimSpace(r.Header.Get("X-Archive-Generation"))
	if genHeader == "" {
		writeWorkspaceArchiveError(w, http.StatusBadRequest, "WORKSPACE_INVALID_REQUEST", "missing X-Archive-Generation header")
		return
	}
	expectedGen, err := strconv.ParseInt(genHeader, 10, 64)
	if err != nil || expectedGen < 0 {
		writeWorkspaceArchiveError(w, http.StatusBadRequest, "WORKSPACE_INVALID_REQUEST", "invalid X-Archive-Generation header")
		return
	}

	allowOverwrite := strings.EqualFold(strings.TrimSpace(r.Header.Get("X-Allow-Overwrite")), "true")
	expectedWorkspaceID := strings.TrimSpace(r.Header.Get("X-Workspace-Id"))
	if expectedWorkspaceID == "" {
		expectedWorkspaceID = strings.TrimSpace(r.URL.Query().Get("workspaceId"))
	}
	if expectedWorkspaceID == "" {
		expectedWorkspaceID = strings.TrimSpace(os.Getenv("WORKSPACE_ID"))
	}

	if !allowOverwrite {
		nonEmpty, err := workspaceHasUserContent(workspaceDir)
		if err != nil {
			writeWorkspaceArchiveError(w, http.StatusInternalServerError, "WORKSPACE_INTERNAL", "failed to inspect workspace: "+err.Error())
			return
		}
		if nonEmpty {
			writeWorkspaceArchiveError(w, http.StatusConflict, "WORKSPACE_RESTORE_NOT_EMPTY", "workspace already contains files")
			return
		}
	}

	runID := randomRestoreID()
	stagingDir := filepath.Join(workspaceDir, filepath.FromSlash(workspaceRestoreStagingRel), runID)
	defer func() {
		_ = os.RemoveAll(stagingDir)
		_ = os.Remove(filepath.Join(workspaceDir, filepath.FromSlash(workspaceRestoreStagingRel)))
	}()

	manifest, restoredFiles, restoredBytes, err := extractWorkspaceArchiveToStaging(r.Body, stagingDir, expectedGen, expectedWorkspaceID)
	if err != nil {
		switch {
		case errors.Is(err, errArchiveGenerationMismatch):
			writeWorkspaceArchiveError(w, http.StatusConflict, "WORKSPACE_ARCHIVE_GENERATION_MISMATCH", err.Error())
		case errors.Is(err, errArchiveWorkspaceMismatch):
			writeWorkspaceArchiveError(w, http.StatusConflict, "WORKSPACE_ARCHIVE_WORKSPACE_MISMATCH", err.Error())
		case errors.Is(err, errArchiveManifestMissing), errors.Is(err, errArchiveInvalidFormat):
			writeWorkspaceArchiveError(w, http.StatusBadRequest, "WORKSPACE_INVALID_ARCHIVE", err.Error())
		default:
			writeWorkspaceArchiveError(w, http.StatusBadRequest, "WORKSPACE_RESTORE_FAILED", err.Error())
		}
		return
	}

	if err := promoteRestoredWorkspace(workspaceDir, stagingDir, runID); err != nil {
		writeWorkspaceArchiveError(w, http.StatusInternalServerError, "WORKSPACE_RESTORE_FAILED", err.Error())
		return
	}
	invalidateWorkspaceEnvCache()

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(workspaceRestoreResponse{
		Status:           "ok",
		WorkspaceID:      manifest.WorkspaceID,
		VolumeGeneration: manifest.VolumeGeneration,
		FileCount:        restoredFiles,
		BytesRestored:    restoredBytes,
	})
}
