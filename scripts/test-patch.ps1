<#
.SYNOPSIS
MPPatch test lifecycle orchestrator for AI agent use.

.DESCRIPTION
Orchestrates the full MPPatch test lifecycle:
  validate -> install -> launch game -> collect logs -> analyze -> uninstall -> report

.PARAMETER CivPath
Path to Civilization V directory. Auto-detected if omitted.

.PARAMETER ExeVariant
Game executable variant: "dx9", "dx11", or "tablet". Default: "dx9".

.PARAMETER TimeoutSeconds
How long to wait for the game to load the DLL after launch. Default: 120.

.PARAMETER SkipGame
Skip game launching (useful for CI without a display or Steam).

.PARAMETER OutputDir
Where to write JSON results and log snapshots. Default: "./test-output".

.PARAMETER Packages
Comma-separated packages to enable. Default: "logging,luajit,multiplayer".

.PARAMETER SbtMode
Use "sbt cli" instead of native image binary.

.EXAMPLE
.\scripts\test-patch.ps1
Full test with auto-detected Civ5 path, DX9, all packages.

.EXAMPLE
.\scripts\test-patch.ps1 -SkipGame -CivPath "D:\Games\Civ5"
Install/uninstall cycle only, no game launch.
#>

param(
    [string]$CivPath,
    [ValidateSet("dx9", "dx11", "tablet")]
    [string]$ExeVariant = "dx9",
    [int]$TimeoutSeconds = 120,
    [switch]$SkipGame,
    [string]$OutputDir = "./test-output",
    [string]$Packages = "logging,luajit,multiplayer",
    [switch]$SbtMode
)

$ErrorActionPreference = "Stop"
$script:StartTime = Get-Date

# ---- helpers ----

function Write-Step {
    param([string]$Phase, [string]$Message)
    $ts = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")
    Write-Host "[$ts] [$Phase] $Message"
}

function Get-MppatchCLI {
    if ($SbtMode) {
        return @("sbt", "cli", "--")
    } else {
        return @("java", "-jar", "target/scala-3.3.1/mppatch-installer-assembly-0.2.0.jar")
    }
}

function Invoke-CliCommand {
    param([string[]]$Args)
    $cli = Get-MppatchCLI
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $cli[0]
    $psi.Arguments = ($cli[1..$cli.Length] + $Args) -join " "
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()

    if ($stderr) { Write-Host $stderr }
    return @{
        ExitCode = $proc.ExitCode
        StdOut   = $stdout
        StdErr   = $stderr
    }
}

# ---- Phase 1: Validate environment ----

Write-Step "validate" "=== Starting MPPatch test lifecycle ==="
Write-Step "validate" "Exe variant: $ExeVariant"
Write-Step "validate" "Timeout: ${TimeoutSeconds}s"
Write-Step "validate" "Output dir: $OutputDir"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}
$OutputDir = Resolve-Path $OutputDir

if (-not $SkipGame) {
    $steamProcess = Get-Process -Name "steam" -ErrorAction SilentlyContinue
    if (-not $steamProcess) {
        $steamExe = "${env:ProgramFiles(x86)}\Steam\Steam.exe"
        if (-not (Test-Path $steamExe)) {
            $steamExe = "${env:ProgramFiles}\Steam\Steam.exe"
        }
        if (Test-Path $steamExe) {
            Write-Step "validate" "Starting Steam..."
            Start-Process $steamExe -ArgumentList "-silent"
            Start-Sleep 15
        } else {
            Write-Step "validate" "WARNING: Steam not found. Game launch may fail."
        }
    } else {
        Write-Step "validate" "Steam is running."
    }
}

# ---- Phase 2: Pre-install check ----

Write-Step "precheck" "Checking pre-install state..."
$preResult = Invoke-CliCommand -Args @("check", "--json")
if ($CivPath) {
    $preResult = Invoke-CliCommand -Args @("check", "--path", $CivPath, "--json")
}
$preJson = $preResult.StdOut | ConvertFrom-Json
$civPathActual = $preJson.civPath
if (-not $civPathActual -or $civPathActual -eq "(auto-detect)") {
    Write-Step "precheck" "ERROR: Could not resolve Civ5 path. Use -CivPath to specify."
    exit 1
}
Write-Step "precheck" "Civ5 path: $civPathActual"
Write-Step "precheck" "Pre-install status: $($preJson.status)"
$preJson | ConvertTo-Json -Depth 5 | Out-File (Join-Path $OutputDir "step-01-preinstall.json") -Encoding utf8

# ---- Phase 3: Install ----

Write-Step "install" "Installing patch with packages: $Packages"
$installArgs = @("install", "--path", $civPathActual, "--packages", $Packages, "--verbose")
$installResult = Invoke-CliCommand -Args $installArgs
$installJson = $installResult.StdOut | ConvertFrom-Json
$installJson | ConvertTo-Json -Depth 5 | Out-File (Join-Path $OutputDir "step-02-install.json") -Encoding utf8

if (-not $installJson.success) {
    Write-Step "install" "ERROR: Install failed: $($installJson.message)"
    exit 1
}
Write-Step "install" "Install successful. Status: $($installJson.status)"

# Verify installed files exist
$dllFiles = @(
    "mppatch_core.dll",
    "mppatch_core_wrapper.dll",
    "mppatch_config.toml",
    "lua51.dll"
)
foreach ($f in $dllFiles) {
    $p = Join-Path $civPathActual $f
    if (Test-Path $p) {
        Write-Step "install" "  Found: $f"
    } else {
        Write-Step "install" "  MISSING: $f"
    }
}

# ---- Phase 4: Launch game ----

if (-not $SkipGame) {
    Write-Step "launch" "Launching Civilization V..."

    $exeMap = @{
        "dx9"    = "CivilizationV.exe"
        "dx11"   = "CivilizationV_DX11.exe"
        "tablet" = "CivilizationV_Tablet.exe"
    }
    $exeName = $exeMap[$ExeVariant]
    $exePath = Join-Path $civPathActual $exeName
    if (-not (Test-Path $exePath)) {
        Write-Step "launch" "WARNING: $exeName not found at expected path."
        $found = Get-ChildItem $civPathActual "CivilizationV*.exe" -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
        if ($found) {
            $exePath = $found
            Write-Step "launch" "  Using: $exePath"
        }
    }

    # Remove any previous log files so we get clean markers
    @("mppatch_ctor.txt", "mppatch_debug.log", "mppatch_fatal_error.txt") | ForEach-Object {
        $p = Join-Path $civPathActual $_
        if (Test-Path $p) { Remove-Item $p -Force }
    }

    # Launch the game
    Write-Step "launch" "Starting $exePath ..."
    $gameProc = Start-Process -FilePath $exePath -WorkingDirectory $civPathActual -PassThru

    # Wait for DLL to load
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $ctorSeen = $false
    $debugLogSeen = $false
    $fatalSeen = $false

    Write-Step "launch" "Waiting for DLL load markers (timeout: ${TimeoutSeconds}s)..."
    while ((Get-Date) -lt $deadline) {
        if (-not $ctorSeen -and (Test-Path (Join-Path $civPathActual "mppatch_ctor.txt"))) {
            $ctorContent = Get-Content (Join-Path $civPathActual "mppatch_ctor.txt") -Raw
            $ctorSeen = $true
            Write-Step "launch" "  DLL ctor marker found: $($ctorContent.Trim())"
        }

        if (-not $debugLogSeen -and (Test-Path (Join-Path $civPathActual "mppatch_debug.log"))) {
            $debugLogSeen = $true
            Write-Step "launch" "  Debug log appeared."
        }

        if (-not $fatalSeen -and (Test-Path (Join-Path $civPathActual "mppatch_fatal_error.txt"))) {
            $fatalContent = Get-Content (Join-Path $civPathActual "mppatch_fatal_error.txt") -Raw
            $fatalSeen = $true
            Write-Step "launch" "  FATAL ERROR FILE: $($fatalContent.Trim())"
            break
        }

        if ($ctorSeen -and $debugLogSeen) {
            Write-Step "launch" "  All DLL markers found."
            break
        }

        if ($gameProc.HasExited) {
            Write-Step "launch" "  Game process exited (code: $($gameProc.ExitCode))."
            break
        }

        Start-Sleep -Seconds 2
    }

    if (-not $ctorSeen -and -not $debugLogSeen) {
        Write-Step "launch" "  WARNING: Timed out waiting for DLL markers."
    }

    # Wait a couple more seconds for additional log output
    Start-Sleep -Seconds 5

    # Kill game
    if (-not $gameProc.HasExited) {
        Write-Step "launch" "  Terminating game process..."
        $gameProc.Kill()
        Start-Sleep -Seconds 2
    }

    Write-Step "launch" "Game phase complete."
} else {
    Write-Step "launch" "Skipped (SkipGame)."
}

# ---- Phase 5: Collect logs ----

Write-Step "collect" "Collecting log files..."
$logDir = Join-Path $OutputDir "logs-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

$logPatterns = @(
    "mppatch_*.txt",
    "mppatch_*.log",
    "mppatch_*.toml",
    "mppatch_*.xml"
)
$collectedFiles = @{}
foreach ($pattern in $logPatterns) {
    $files = Get-ChildItem $civPathActual -Filter $pattern -ErrorAction SilentlyContinue
    foreach ($f in $files) {
        $dest = Join-Path $logDir $f.Name
        Copy-Item $f.FullName $dest -Force
        $collectedFiles[$f.Name] = $dest
        Write-Step "collect" "  Copied: $($f.Name)"
    }
}

# ---- Phase 6: Analyze logs ----

Write-Step "analyze" "Analyzing DLL logs..."
$analysis = @{
    ctorExists     = $false
    ctorContent    = $null
    debugLogExists = $false
    successMarkers = @()
    errorMarkers   = @()
    fatalError     = $false
    fatalContent   = $null
    passed         = $false
}

$ctorPath = Join-Path $civPathActual "mppatch_ctor.txt"
if (Test-Path $ctorPath) {
    $analysis.ctorExists = $true
    $analysis.ctorContent = (Get-Content $ctorPath -Raw).Trim()
}

$debugPath = Join-Path $civPathActual "mppatch_debug.log"
if (Test-Path $debugPath) {
    $analysis.debugLogExists = $true
    $lines = Get-Content $debugPath
    $analysis.successMarkers = @($lines | Where-Object {
        $_ -match "mppatch-core v.* loaded" -or
        $_ -match "Applying SetActiveDLCAndMods patch" -or
        $_ -match "Applying lGetMemoryUsage patch" -or
        $_ -match "Game version:"
    })
    $analysis.errorMarkers = @($lines | Where-Object {
        $_ -match "Error occurred" -or
        $_ -match "panicked at" -or
        $_ -match "Internal error:"
    })
}

$fatalPath = Join-Path $civPathActual "mppatch_fatal_error.txt"
if (Test-Path $fatalPath) {
    $analysis.fatalError = $true
    $analysis.fatalContent = (Get-Content $fatalPath -Raw).Trim()
}

$analysis.passed = (
    $analysis.ctorExists -and
    $analysis.successMarkers.Count -gt 0 -and
    $analysis.errorMarkers.Count -eq 0 -and
    -not $analysis.fatalError
)

Write-Step "analyze" "  ctor exists: $($analysis.ctorExists)"
Write-Step "analyze" "  debug log: $($analysis.debugLogExists)"
Write-Step "analyze" "  success markers: $($analysis.successMarkers.Count)"
Write-Step "analyze" "  error markers: $($analysis.errorMarkers.Count)"
Write-Step "analyze" "  fatal error: $($analysis.fatalError)"
Write-Step "analyze" "  PASSED: $($analysis.passed)"

$analysis | ConvertTo-Json -Depth 3 | Out-File (Join-Path $OutputDir "step-03-analysis.json") -Encoding utf8

# ---- Phase 7: Uninstall ----

Write-Step "uninstall" "Uninstalling patch..."
$uninstallResult = Invoke-CliCommand -Args @("uninstall", "--path", $civPathActual, "--verbose")
$uninstallJson = $uninstallResult.StdOut | ConvertFrom-Json
$uninstallJson | ConvertTo-Json -Depth 5 | Out-File (Join-Path $OutputDir "step-04-uninstall.json") -Encoding utf8

Write-Step "uninstall" "Uninstall status: $($uninstallJson.status)"

# ---- Phase 8: Post-uninstall check ----

Write-Step "verify" "Verifying clean state..."
$postResult = Invoke-CliCommand -Args @("check", "--path", $civPathActual, "--json")
$postJson = $postResult.StdOut | ConvertFrom-Json
$postJson | ConvertTo-Json -Depth 5 | Out-File (Join-Path $OutputDir "step-05-postclean.json") -Encoding utf8
Write-Step "verify" "Post-uninstall status: $($postJson.status)"

# ---- Phase 9: Summary report ----

Write-Step "report" "Generating summary report..."

$summary = [PSCustomObject]@{
    schemaVersion  = "1.0"
    timestamp      = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")
    exeVariant     = $ExeVariant
    civPath        = $civPathActual
    packages       = $Packages
    skipGame       = $SkipGame
    durationSeconds = [math]::Round(((Get-Date) - $script:StartTime).TotalSeconds, 1)

    phases = [PSCustomObject]@{
        preInstall  = [PSCustomObject]@{ success = $true; status = $preJson.status }
        install     = [PSCustomObject]@{ success = $installJson.success; status = $installJson.status }
        dllAnalysis = $analysis
        uninstall   = [PSCustomObject]@{ success = $uninstallJson.success; status = $uninstallJson.status }
        postClean   = [PSCustomObject]@{ success = $true; status = $postJson.status }
    }

    overallPassed = ($installJson.success -and $analysis.passed -and $uninstallJson.success)
}

$summaryFile = Join-Path $OutputDir "result.json"
$summary | ConvertTo-Json -Depth 6 | Out-File $summaryFile -Encoding utf8

Write-Step "report" ""
Write-Step "report" "=== TEST COMPLETE ==="
Write-Step "report" "Overall: $(if ($summary.overallPassed) { 'PASSED' } else { 'FAILED' })"
Write-Step "report" "Duration: $($summary.durationSeconds)s"
Write-Step "report" "Report: $summaryFile"
Write-Step "report" ""

exit $(if ($summary.overallPassed) { 0 } else { 1 })
