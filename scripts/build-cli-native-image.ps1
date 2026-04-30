<#
.SYNOPSIS
Build the MPPatch CLI native image.

.DESCRIPTION
Builds the fat JAR via sbt assembly, then invokes GraalVM native-image
with CLI-specific configs (no AWT/Swing/FlatLaf). The output binary is
much smaller than the GUI native image (~10-15 MB vs ~40+ MB).
#>

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "=== Building MPPatch CLI native image ==="

# Step 1: Build fat JAR
Write-Host "[1/3] Building fat JAR..."
Push-Location $projectRoot
try {
    sbt assembly
    if ($LASTEXITCODE -ne 0) {
        throw "sbt assembly failed"
    }
} finally {
    Pop-Location
}

# Step 2: Locate the JAR
$jarDir = Join-Path $projectRoot "target" "scala-3.3.1"
$jarFile = Get-ChildItem $jarDir -Filter "mppatch-installer-assembly-*.jar" |
    Where-Object { $_.Name -notmatch "javadoc|sources" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jarFile) {
    throw "Could not find assembly JAR in $jarDir"
}
Write-Host "[2/3] Found JAR: $($jarFile.FullName)"

# Step 3: Determine platform
$osName = (Get-WmiObject Win32_OperatingSystem).Caption
if ($osName -match "Windows") {
    $platform = "win32"
} elseif ($osName -match "Linux") {
    $platform = "linux"
} else {
    throw "Unknown platform: $osName"
}

$graalHome = Join-Path $projectRoot "target" "deps" "graalvm-$platform"
$nativeImageExe = Join-Path $graalHome "bin" "native-image.cmd"
if (-not (Test-Path $nativeImageExe)) {
    $nativeImageExe = Join-Path $graalHome "bin" "native-image"
}
if (-not (Test-Path $nativeImageExe)) {
    Write-Host "GraalVM not found at $graalHome"
    Write-Host "Run scripts/ci/install-deps.ps1 first to download GraalVM."
    exit 1
}

$outputDir = Join-Path $projectRoot "target" "native-image-cli-$platform"
$outputExe = Join-Path $outputDir "mppatch-cli.exe"
$configDir = Join-Path $projectRoot "scripts" "native-image-config" "cli"

New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

Write-Host "[3/3] Running native-image..."
$args = @(
    "--no-fallback",
    "--strict-image-heap",
    "-H:ConfigurationFileDirectories=$configDir",
    "-jar", $jarFile.FullName,
    "-H:Class=moe.lymia.mppatch.cli.MPPatchCLI",
    "-o", $outputExe
)

Write-Host "  native-image $($args -join ' ')"
& $nativeImageExe $args

if ($LASTEXITCODE -ne 0) {
    throw "native-image failed"
}

Write-Host ""
Write-Host "=== CLI native image built successfully ==="
Write-Host "Output: $outputExe"

$size = (Get-Item $outputExe).Length / 1MB
Write-Host "Size: $([math]::Round($size, 1)) MB"
