package main

import (
	"encoding/json"
	"net/http"
	"syscall"
)

type WorkspaceStatResponse struct {
	CapacityBytes    uint64 `json:"capacity_bytes"`
	UsedBytes        uint64 `json:"used_bytes"`
	AvailableBytes   uint64 `json:"available_bytes"`
	InodesTotal      uint64 `json:"inodes_total"`
	InodesUsed       uint64 `json:"inodes_used"`
	ActiveAsyncTasks int    `json:"active_async_tasks"`
	ActiveTerminals  int    `json:"active_terminals"`
}

func handleWorkspaceStat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var stat syscall.Statfs_t
	if err := syscall.Statfs(workspaceDir, &stat); err != nil {
		http.Error(w, "failed to stat workspace: "+err.Error(), http.StatusInternalServerError)
		return
	}

	var bsize uint64
	if stat.Bsize > 0 {
		bsize = uint64(stat.Bsize)
	}
	capacity := stat.Blocks * bsize
	var usedBlocks uint64
	if stat.Blocks > stat.Bfree {
		usedBlocks = stat.Blocks - stat.Bfree
	}
	used := usedBlocks * bsize
	available := stat.Bavail * bsize
	var inodesUsed uint64
	if stat.Files > stat.Ffree {
		inodesUsed = stat.Files - stat.Ffree
	}

	activeAsync := 0
	if taskRegistry != nil {
		activeAsync = taskRegistry.RunningCount()
	}
	activeTerms := 0
	if activeTerminalRegistry != nil && activeTerminalRegistry.HasActive() {
		activeTerms = 1
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(WorkspaceStatResponse{
		CapacityBytes:    capacity,
		UsedBytes:        used,
		AvailableBytes:   available,
		InodesTotal:      stat.Files,
		InodesUsed:       inodesUsed,
		ActiveAsyncTasks: activeAsync,
		ActiveTerminals:  activeTerms,
	})
}
