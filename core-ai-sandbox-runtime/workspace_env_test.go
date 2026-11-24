package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseWorkspaceEnvFollowsTheDeclaredContract(t *testing.T) {
	content := "\ufeff# a comment\r\n" +
		"\r\n" +
		"FOO=first\r\n" +
		"export BAR = spaced value \r\n" +
		"FOO=last\n" +
		"WITH_EQUALS=a=b=c\n" +
		"HOME=/workspace/home\n" +
		"CORE_AI_SESSION_ID=forged\n" +
		"PATH=/evil\n" +
		"NOT_AN_ASSIGNMENT\n" +
		"1BAD=x\n"

	values, warnings := parseWorkspaceEnv(content)

	if values["FOO"] != "last" {
		t.Fatalf("last duplicate should win, got %q", values["FOO"])
	}
	if values["BAR"] != "spaced value" {
		t.Fatalf("quoted-looking value should stay literal after trimming, got %q", values["BAR"])
	}
	if values["WITH_EQUALS"] != "a=b=c" {
		t.Fatalf("only the first '=' splits, got %q", values["WITH_EQUALS"])
	}
	for _, key := range []string{"HOME", "CORE_AI_SESSION_ID", "PATH", "NOT_AN_ASSIGNMENT", "1BAD"} {
		if _, ok := values[key]; ok {
			t.Fatalf("%s must not be accepted", key)
		}
	}
	if len(warnings) != 5 {
		t.Fatalf("expected 5 warnings for rejected lines, got %d: %v", len(warnings), warnings)
	}
}

func TestParseWorkspaceEnvIgnoresAnOversizedFileAndLine(t *testing.T) {
	line := "LONG=" + strings.Repeat("x", maxWorkspaceEnvLineBytes)
	values, warnings := parseWorkspaceEnv(line + "\nOK=1\n")

	if _, ok := values["LONG"]; ok {
		t.Fatal("an oversized line must not be partially applied")
	}
	if values["OK"] != "1" {
		t.Fatal("valid lines around an oversized one still apply")
	}
	if len(warnings) != 1 {
		t.Fatalf("expected one warning, got %v", warnings)
	}
}

func TestMinimalEnvInjectsWorkspaceEnvAndKeepsOwnershipOfProtectedKeys(t *testing.T) {
	useTempWorkspace(t)
	envFile := filepath.Join(t.TempDir(), "env")
	t.Setenv("WORKSPACE_ENV_FILE", envFile)
	t.Setenv("HOME", filepath.Join(t.TempDir(), "home"))
	t.Setenv("UNCHANGED_FROM_PARENT", "yes")
	if err := os.WriteFile(envFile, []byte(""+
		"FOO=bar\n"+
		"HOME=/hijacked\n"+
		"PATH=/hijacked\n"+
		"CORE_AI_SESSION_ID=forged\n"+
		"PATH_PREPEND=/custom/bin\n"), 0644); err != nil {
		t.Fatal(err)
	}

	env := envMap(minimalEnv())

	if env["FOO"] != "bar" {
		t.Fatalf("workspace env value not injected: %q", env["FOO"])
	}
	if env["HOME"] != os.Getenv("HOME") {
		t.Fatalf("HOME must stay owned by the runtime, got %q", env["HOME"])
	}
	if strings.Contains(env["PATH"], "/hijacked") || !strings.Contains(env["PATH"], "/custom/bin") {
		t.Fatalf("PATH may only be extended through PATH_PREPEND, got %q", env["PATH"])
	}
	if env["CORE_AI_SESSION_ID"] == "forged" {
		t.Fatal("CORE_AI_* keys must stay reserved")
	}
	if env["UNCHANGED_FROM_PARENT"] != "yes" {
		t.Fatal("parent env must still be inherited")
	}
	if env["CORE_AI_CRED_DIR"] != defaultCredentialDir {
		t.Fatalf("credential dir must be platform-owned, got %q", env["CORE_AI_CRED_DIR"])
	}
	if env["GH_CONFIG_DIR"] != defaultCredentialDir+"/gh" {
		t.Fatalf("gh config dir must stay under the runtime credential dir, got %q", env["GH_CONFIG_DIR"])
	}
	if !strings.HasSuffix(env["NPM_CONFIG_PREFIX"], ".npm-global") {
		t.Fatalf("npm global prefix must live on the workspace, got %q", env["NPM_CONFIG_PREFIX"])
	}
}

func TestExecuteBashAppliesWorkspaceEnv(t *testing.T) {
	workspace := useTempWorkspace(t)
	envFile := filepath.Join(workspace, ".core-ai", "env")
	if err := os.MkdirAll(filepath.Dir(envFile), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(envFile, []byte("CORE_TEST_VALUE=from-workspace\n"), 0644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("WORKSPACE_ENV_FILE", envFile)

	result, status := executeBash(`{"command": "printf '%s' \"$CORE_TEST_VALUE\""}`)

	if status != "completed" || result != "from-workspace" {
		t.Fatalf("status=%q result=%q", status, result)
	}
}

func TestExecuteBashSourcesProfileInASubshellAndKeepsToolOptions(t *testing.T) {
	workspace := useTempWorkspace(t)
	profile := filepath.Join(workspace, ".core-ai", "profile.sh")
	if err := os.MkdirAll(filepath.Dir(profile), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(profile, []byte("set -euo pipefail\nexit 9\n"), 0644); err != nil {
		t.Fatal(err)
	}
	logDir := t.TempDir()
	t.Setenv("WORKSPACE_PROFILE_FILE", filepath.ToSlash(profile))
	t.Setenv("WORKSPACE_LOG_DIR", filepath.ToSlash(logDir))

	result, status := executeBash(`{"command": "echo still-running; false; echo after-false"}`)

	if status != "completed" {
		t.Fatalf("status = %q, result: %s", status, result)
	}
	if !strings.Contains(result, "still-running") || !strings.Contains(result, "after-false") {
		t.Fatalf("profile must not change the tool command shell options; result: %s", result)
	}
}

func TestExecuteBashDoesNotInheritBashEnv(t *testing.T) {
	workspace := useTempWorkspace(t)
	marker := filepath.Join(workspace, "bash-env-ran")
	bashEnv := filepath.Join(workspace, "bash-env.sh")
	if err := os.WriteFile(bashEnv, []byte("touch "+filepath.ToSlash(marker)+"\n"), 0644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BASH_ENV", filepath.ToSlash(bashEnv))

	result, status := executeBash(`{"command": "bash -c 'echo nested-ok'"}`)

	if status != "completed" || !strings.Contains(result, "nested-ok") {
		t.Fatalf("status=%q result=%q", status, result)
	}
	if _, err := os.Stat(marker); err == nil {
		t.Fatal("BASH_ENV must not leak into nested bash invocations")
	}
}
