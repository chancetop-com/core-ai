param(
    [int]$Trials = 1,
    [int]$Concurrency = 1,
    [string]$Model = "litellm/deepseek-flash",
    [string]$ThinkingEffort = "max",
    [string]$Task = "",
    [int]$NTasks = 0,
    [string]$JobsDir = "",
    [string]$JobName = "",
    [string]$DatasetDir = "",
    [string]$DatasetRef = "main",
    [string]$CliVersion = "2.0.61",
    [string]$CliBinary = "",
    [string]$DisabledTools = "web_search,web_fetch",
    [string]$NetworkMode = "",
    [string[]]$AllowHost = @(),
    [int]$TimeBudgetSeconds = -1,
    [int]$BudgetMarginSeconds = 15,
    [int]$TokenBudget = -1,
    [double]$CostBudgetUsd = 0,
    [int]$ReserveTokens = 0,
    [double]$ReserveCostUsd = 0,
    [switch]$AutoTimeBudget = $true,
    [string]$HarborExe = "",
    [switch]$UpdateDataset,
    [switch]$Help
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$env:PYTHONUTF8 = "1"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($Help) {
    @"
Usage: .\run.ps1 [options]

Runs the full Terminal-Bench 2.1 dataset (89 tasks) against core-ai-cli.
Defaults: 1 trial, 1 concurrent trial, model litellm/deepseek-flash,
thinking effort max.

Options:
  -Trials N            Trials per task (default: 1; official leaderboard uses 5)
  -Concurrency N       Concurrent trials (default: 1; raise after increasing Docker memory)
  -Model MODEL         Model in provider/model form (default: litellm/deepseek-flash)
  -ThinkingEffort E    Reasoning effort: none|low|medium|high|max (default: max)
  -Task NAME           Run one task only
  -NTasks N            Run the first N tasks only
  -JobsDir DIR         Harbor jobs directory (default: .harbor-jobs)
  -JobName NAME        Reuse an existing job name to resume an interrupted run
  -DatasetDir DIR      Local Terminal-Bench 2.1 checkout
  -DatasetRef REF      Git ref for the dataset (default: main)
  -CliBinary PATH      Prebuilt Linux core-ai-cli binary
  -CliVersion VER      core-ai-cli release version to download (default: 2.0.61)
  -DisabledTools CSV   Tools hidden from the agent (default: web_search,web_fetch)
  -NetworkMode MODE    Pin the dataset network policy: allowlist|no-network|public
                       (default: leave task.toml untouched; see network_policy.py)
  -AllowHost HOST      Extra egress host for every task; repeatable
  -TimeBudgetSeconds N Agent wall-clock budget; -1=auto from task.toml, 0=disable
  -BudgetMarginSeconds N Safety margin for auto budget (default: 15)
  -TokenBudget N       Total token budget; -1=disable
  -CostBudgetUsd N     Total USD budget; 0=disable
  -ReserveTokens N     Tokens kept in reserve
  -ReserveCostUsd N    USD kept in reserve
  -AutoTimeBudget:$false  Disable task.toml budget derivation
  -UpdateDataset       git pull the local dataset before running
  -HarborExe PATH      Harbor executable (default: harbor on PATH)
  -Help                Show this help

Authentication:
  By default the active entry in ~/.core-ai/auth.json (written by
  `core-ai-cli --login`) is used. LITELLM_API_BASE / LITELLM_API_KEY override it.
"@
    exit 0
}

if ($Trials -lt 1) { throw "-Trials must be a positive integer" }
if ($Concurrency -lt 1) { throw "-Concurrency must be a positive integer" }

if (-not $HarborExe) { $HarborExe = "harbor" }
if (-not $JobsDir) { $JobsDir = Join-Path $ScriptDir ".harbor-jobs" }
if (-not $DatasetDir) { $DatasetDir = Join-Path $ScriptDir ".datasets\terminal-bench-2-1" }
$ExplicitCliBinary = -not [string]::IsNullOrWhiteSpace($CliBinary)
if (-not $CliBinary) { $CliBinary = Join-Path $ScriptDir ".cache\core-ai-cli-linux" }

$EnvFile = Join-Path $ScriptDir ".env"
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { Set-Item -Path "env:$($matches[1])" -Value $matches[2] }
    }
}

if (-not $env:LITELLM_API_BASE -or -not $env:LITELLM_API_KEY) {
    $AuthPath = Join-Path $HOME ".core-ai\auth.json"
    if (Test-Path $AuthPath) {
        $Auth = Get-Content -Raw $AuthPath | ConvertFrom-Json |
            Where-Object { $_.active -eq $true } | Select-Object -First 1
        if ($Auth) {
            $env:LITELLM_API_BASE = $Auth.server_url.TrimEnd('/') + "/api/cli/v1"
            $env:LITELLM_API_KEY = $Auth.api_key
        }
    }
}
if (-not $env:LITELLM_API_BASE -or -not $env:LITELLM_API_KEY) {
    throw "No core-ai credentials found. Run 'core-ai-cli --login <server-url>' first, or set LITELLM_API_BASE/LITELLM_API_KEY."
}

if (Test-Path $HarborExe) {
    $HarborCommand = $HarborExe
}
elseif (Get-Command $HarborExe -ErrorAction SilentlyContinue) {
    $HarborCommand = $HarborExe
}
else {
    throw "Harbor is not installed. Run: uv tool install 'harbor==0.24.0'"
}
$HarborVersion = (& $HarborCommand --version 2>&1 | Select-Object -First 1)
Write-Host "Harbor: $HarborVersion"
if ($HarborVersion -match '^(\d+)\.(\d+)') {
    $major = [int]$Matches[1]
    $minor = [int]$Matches[2]
    if ($major -eq 0 -and $minor -lt 24) {
        throw "Harbor 0.24+ is required (found: $HarborVersion). Run: uv tool install 'harbor==0.24.0'"
    }
}

function Ensure-CliBinary {
    $stampFile = "$CliBinary.version"
    if (Test-Path $CliBinary) {
        if ($ExplicitCliBinary -or $CliVersion -eq "latest") { return }
        if ((Test-Path $stampFile) -and ((Get-Content -Raw $stampFile).Trim() -eq $CliVersion)) { return }
    }
    $parent = Split-Path -Parent $CliBinary
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $url = if ($CliVersion -eq "latest") {
        "https://github.com/chancetop-com/core-ai/releases/latest/download/core-ai-cli-linux.tar.gz"
    }
    else {
        "https://github.com/chancetop-com/core-ai/releases/download/v$CliVersion/core-ai-cli-linux.tar.gz"
    }
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("core-ai-cli-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        Write-Host "Downloading core-ai-cli v$CliVersion..."
        Invoke-WebRequest -Uri $url -OutFile (Join-Path $tmp "core-ai-cli-linux.tar.gz")
        tar -xzf (Join-Path $tmp "core-ai-cli-linux.tar.gz") -C $tmp
        Move-Item (Join-Path $tmp "core-ai-cli-linux") $CliBinary -Force
        if (-not $ExplicitCliBinary -and $CliVersion -ne "latest") {
            Set-Content -Path $stampFile -Value $CliVersion -NoNewline
        }
    }
    finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
Ensure-CliBinary

if (-not (Test-Path (Join-Path $DatasetDir "tasks"))) {
    if (Test-Path $DatasetDir) {
        $existing = Get-ChildItem $DatasetDir -Force -ErrorAction SilentlyContinue
        if ($existing) {
            throw "Dataset directory exists but has no tasks/: $DatasetDir"
        }
    }
    Write-Host "Cloning Terminal-Bench 2.1 dataset..."
    if (Test-Path $DatasetDir) { Remove-Item $DatasetDir -Recurse -Force }
    git clone --depth 1 https://github.com/harbor-framework/terminal-bench-2-1.git $DatasetDir
}
if ($UpdateDataset) { git -C $DatasetDir pull --ff-only }
if ($DatasetRef -ne "main") {
    git -C $DatasetDir fetch --depth 1 origin $DatasetRef
    git -C $DatasetDir checkout -q FETCH_HEAD
}

if ($NetworkMode) {
    $PythonForPolicy = Get-Command python -ErrorAction SilentlyContinue
    if (-not $PythonForPolicy) { throw "Python is required for -NetworkMode" }
    $PolicyArgs = @(
        (Join-Path $ScriptDir "network_policy.py"),
        "--dataset-dir", $DatasetDir,
        "--mode", $NetworkMode
    )
    foreach ($host_ in $AllowHost) { $PolicyArgs += @("--extra-host", $host_) }
    & $PythonForPolicy.Source @PolicyArgs
}

$env:PYTHONPATH = $ScriptDir
$HarborArgs = @(
    "run",
    "-p", (Join-Path $DatasetDir "tasks"),
    "-a", "cli:CoreAiCli",
    "--ak", "binary_path=$CliBinary",
    "-n", "$Concurrency",
    "-k", "$Trials",
    "-y",
    "--jobs-dir", $JobsDir
)
if ($ThinkingEffort) { $HarborArgs += @("--ak", "reasoning_effort=$ThinkingEffort") }
if ($DisabledTools) { $HarborArgs += @("--ak", "disabled_tools=$DisabledTools") }
if ($TimeBudgetSeconds -ge 0) { $HarborArgs += @("--ak", "time_budget_seconds=$TimeBudgetSeconds") }
if ($BudgetMarginSeconds -gt 0) { $HarborArgs += @("--ak", "budget_margin_seconds=$BudgetMarginSeconds") }
if (-not $AutoTimeBudget) { $HarborArgs += @("--ak", "auto_time_budget=false") }
if ($TokenBudget -gt 0) { $HarborArgs += @("--ak", "token_budget=$TokenBudget") }
if ($CostBudgetUsd -gt 0) { $HarborArgs += @("--ak", "cost_budget_usd=$CostBudgetUsd") }
if ($ReserveTokens -gt 0) { $HarborArgs += @("--ak", "budget_reserve_tokens=$ReserveTokens") }
if ($ReserveCostUsd -gt 0) { $HarborArgs += @("--ak", "budget_reserve_cost_usd=$ReserveCostUsd") }
if ($Model) { $HarborArgs += @("-m", $Model) }
if ($JobName) { $HarborArgs += @("--job-name", $JobName) }
if ($Task) { $HarborArgs += @("-i", $Task) }
if ($NTasks -gt 0) { $HarborArgs += @("-l", "$NTasks") }

$commit = (git -C $DatasetDir rev-parse --short HEAD)
Write-Host "Dataset: $DatasetDir ($commit)"
Write-Host "core-ai-cli: $CliBinary"
Write-Host "Model: $Model"
Write-Host "Thinking effort: $ThinkingEffort"
Write-Host "Disabled tools: $DisabledTools"
Write-Host "Network mode: $(if ($NetworkMode) { $NetworkMode } else { 'dataset default' })"
Write-Host "Trials: $Trials, concurrency: $Concurrency"
Write-Host "Jobs: $JobsDir"

& $HarborCommand @HarborArgs
$exitCode = $LASTEXITCODE
$Python = Get-Command python -ErrorAction SilentlyContinue
if ($Python -and (Test-Path (Join-Path $ScriptDir "summarize.py"))) {
    try {
        & $Python.Source (Join-Path $ScriptDir "summarize.py") $JobsDir
    }
    catch {
        Write-Warning "Failed to generate report: $_"
    }
}
if ($Python -and (Test-Path (Join-Path $ScriptDir "contamination.py"))) {
    try {
        & $Python.Source (Join-Path $ScriptDir "contamination.py") $JobsDir
    }
    catch {
        Write-Warning "Failed to scan for contamination: $_"
    }
}

Write-Host ""
Write-Host "Results: $JobsDir"
Write-Host "Inspect: $HarborCommand view `"$JobsDir`""
Write-Host "Official leaderboard note: Terminal-Bench 2.1 community submissions are closed."
exit $exitCode

