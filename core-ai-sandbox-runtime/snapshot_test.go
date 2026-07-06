package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"testing"
)

func testRoots(t *testing.T) ([]snapshotRoot, string, string) {
	t.Helper()
	tmpRoot := t.TempDir()
	skillRoot := t.TempDir()
	roots := []snapshotRoot{
		{Name: "tmp", Target: tmpRoot, ArchivePrefix: "roots/tmp"},
		{Name: "skill", Target: skillRoot, ArchivePrefix: "roots/skill"},
	}
	return roots, tmpRoot, skillRoot
}

func writeFile(t *testing.T, path, content string, mode os.FileMode) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), mode); err != nil {
		t.Fatal(err)
	}
}

func TestSnapshotRoundTrip(t *testing.T) {
	src, srcTmp, srcSkill := testRoots(t)
	writeFile(t, filepath.Join(srcTmp, "data/report.csv"), "a,b,c", 0644)
	writeFile(t, filepath.Join(srcTmp, "run.sh"), "#!/bin/sh\necho hi", 0755)
	writeFile(t, filepath.Join(srcSkill, "review/skill.md"), "skill body", 0644)

	var buf bytes.Buffer
	count, err := buildSnapshotArchive(&buf, src)
	if err != nil {
		t.Fatalf("build failed: %v", err)
	}
	if count != 3 {
		t.Fatalf("expected 3 files, got %d", count)
	}

	dst, dstTmp, dstSkill := testRoots(t)
	restored, err := restoreSnapshotArchive(bytes.NewReader(buf.Bytes()), dst)
	if err != nil {
		t.Fatalf("restore failed: %v", err)
	}
	if restored != 3 {
		t.Fatalf("expected 3 restored files, got %d", restored)
	}
	got, err := os.ReadFile(filepath.Join(dstTmp, "data/report.csv"))
	if err != nil || string(got) != "a,b,c" {
		t.Fatalf("file content mismatch: %v %q", err, got)
	}
	info, err := os.Stat(filepath.Join(dstTmp, "run.sh"))
	if err != nil || info.Mode().Perm() != 0755 {
		t.Fatalf("exec bit lost: %v %v", err, info.Mode())
	}
	if _, err := os.Stat(filepath.Join(dstSkill, "review/skill.md")); err != nil {
		t.Fatalf("skill file missing: %v", err)
	}
}

func TestSnapshotExcludes(t *testing.T) {
	src, srcTmp, _ := testRoots(t)
	writeFile(t, filepath.Join(srcTmp, "keep.txt"), "keep", 0644)
	writeFile(t, filepath.Join(srcTmp, "app.sock"), "x", 0644)
	writeFile(t, filepath.Join(srcTmp, "pkg/__pycache__/mod.pyc"), "x", 0644)
	writeFile(t, filepath.Join(srcTmp, ".X11-unix/X0"), "x", 0644)

	var buf bytes.Buffer
	count, err := buildSnapshotArchive(&buf, src)
	if err != nil {
		t.Fatalf("build failed: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected only keep.txt, got %d files", count)
	}
}

func TestSnapshotSkipsSymlinks(t *testing.T) {
	src, srcTmp, _ := testRoots(t)
	writeFile(t, filepath.Join(srcTmp, "real.txt"), "real", 0644)
	if err := os.Symlink("/etc/passwd", filepath.Join(srcTmp, "evil-link")); err != nil {
		t.Skip("symlink not supported")
	}
	var buf bytes.Buffer
	count, err := buildSnapshotArchive(&buf, src)
	if err != nil {
		t.Fatalf("build failed: %v", err)
	}
	if count != 1 {
		t.Fatalf("symlink should be skipped, got %d files", count)
	}
}

func TestRestoreRejectsPathEscape(t *testing.T) {
	// Hand-craft an archive whose entry tries to climb out of the root.
	var buf bytes.Buffer
	gw := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gw)
	evil := []byte("evil")
	if err := tw.WriteHeader(&tar.Header{Name: "roots/tmp/../../escape.txt", Mode: 0644, Size: int64(len(evil)), Typeflag: tar.TypeReg}); err != nil {
		t.Fatal(err)
	}
	if _, err := tw.Write(evil); err != nil {
		t.Fatal(err)
	}
	tw.Close()
	gw.Close()

	dst, dstTmp, _ := testRoots(t)
	if _, err := restoreSnapshotArchive(bytes.NewReader(buf.Bytes()), dst); err == nil {
		t.Fatal("expected escape entry to be rejected")
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(dstTmp), "escape.txt")); err == nil {
		t.Fatal("escaped file must not exist")
	}
}

func TestRestoreSkipsSymlinkEntries(t *testing.T) {
	var buf bytes.Buffer
	gw := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gw)
	if err := tw.WriteHeader(&tar.Header{Name: "roots/tmp/link", Linkname: "/etc/passwd", Typeflag: tar.TypeSymlink}); err != nil {
		t.Fatal(err)
	}
	content := []byte("ok")
	if err := tw.WriteHeader(&tar.Header{Name: "roots/tmp/ok.txt", Mode: 0644, Size: int64(len(content)), Typeflag: tar.TypeReg}); err != nil {
		t.Fatal(err)
	}
	if _, err := tw.Write(content); err != nil {
		t.Fatal(err)
	}
	tw.Close()
	gw.Close()

	dst, dstTmp, _ := testRoots(t)
	n, err := restoreSnapshotArchive(bytes.NewReader(buf.Bytes()), dst)
	if err != nil {
		t.Fatalf("restore failed: %v", err)
	}
	if n != 1 {
		t.Fatalf("expected 1 restored file, got %d", n)
	}
	if _, err := os.Lstat(filepath.Join(dstTmp, "link")); err == nil {
		t.Fatal("symlink entry must be skipped")
	}
}

func TestRestoreRejectsDecompressionBomb(t *testing.T) {
	old := maxSnapshotTotalSize
	maxSnapshotTotalSize = 64
	defer func() { maxSnapshotTotalSize = old }()

	// Hand-craft a valid archive whose single regular file's uncompressed
	// content exceeds the lowered cap; repetitive bytes compress tightly,
	// mimicking a small compressed body that inflates far past the limit.
	var buf bytes.Buffer
	gw := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gw)
	big := bytes.Repeat([]byte("A"), 4096)
	if err := tw.WriteHeader(&tar.Header{Name: "roots/tmp/bomb.txt", Mode: 0644, Size: int64(len(big)), Typeflag: tar.TypeReg}); err != nil {
		t.Fatal(err)
	}
	if _, err := tw.Write(big); err != nil {
		t.Fatal(err)
	}
	tw.Close()
	gw.Close()

	dst, _, _ := testRoots(t)
	if _, err := restoreSnapshotArchive(bytes.NewReader(buf.Bytes()), dst); err == nil {
		t.Fatal("expected decompression bomb to be rejected")
	}
}

func TestSnapshotFileCountCap(t *testing.T) {
	old := maxSnapshotFiles
	maxSnapshotFiles = 2
	defer func() { maxSnapshotFiles = old }()

	src, srcTmp, _ := testRoots(t)
	writeFile(t, filepath.Join(srcTmp, "a.txt"), "a", 0644)
	writeFile(t, filepath.Join(srcTmp, "b.txt"), "b", 0644)
	writeFile(t, filepath.Join(srcTmp, "c.txt"), "c", 0644)

	var buf bytes.Buffer
	if _, err := buildSnapshotArchive(&buf, src); err == nil {
		t.Fatal("expected file count cap error")
	}
}
