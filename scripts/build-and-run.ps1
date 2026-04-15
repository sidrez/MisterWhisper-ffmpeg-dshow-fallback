param(
    [switch]$Run,
    [string]$FfmpegDevice
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$runtimeDir = Join-Path $repoRoot "runtime\win32-x86-64"
$outDir = Join-Path $repoRoot "out"
$outRuntimeDir = Join-Path $outDir "win32-x86-64"
$outWhisperDir = Join-Path $outDir "whisper"

$requiredDlls = @(
    "whisper.dll",
    "ggml.dll",
    "ggml-base.dll",
    "ggml-cpu.dll",
    "ggml-cuda.dll"
)

foreach ($dll in $requiredDlls) {
    if (-not (Test-Path (Join-Path $runtimeDir $dll))) {
        Write-Error "Missing $dll in $runtimeDir. Run scripts\bootstrap-runtime-from-release.ps1 first."
    }
}

New-Item -ItemType Directory -Force -Path $outRuntimeDir | Out-Null
New-Item -ItemType Directory -Force -Path $outWhisperDir | Out-Null
Copy-Item -LiteralPath (Join-Path $runtimeDir "*.dll") -Destination $outRuntimeDir -Force
# Needed for tray icon loading when running from classes in out/
Copy-Item -LiteralPath (Join-Path $repoRoot "src\whisper\*.png") -Destination $outWhisperDir -Force

$sources = Get-ChildItem -Path "src" -Recurse -Filter "*.java" -File |
    Where-Object { $_.Name -notmatch '(?i)copy|копия' -and $_.BaseName -notmatch '\s' } |
    ForEach-Object { $_.FullName }
$classpath = "lib\jna.jar;lib\jnativehook-2.2.2.jar;lib\win32-x86-64.jar"

Write-Host "Compiling Java sources..."
& javac -encoding UTF-8 -cp $classpath -d $outDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

if ($Run) {
    Write-Host "Starting MisterWhisper..."
    if ($FfmpegDevice -and -not [string]::IsNullOrWhiteSpace($FfmpegDevice)) {
        $env:MISTERWHISPER_FFMPEG_DEVICE = $FfmpegDevice
        Write-Host "Using ffmpeg device override: $FfmpegDevice"
    }
    & java -cp "out;$classpath" whisper.MisterWhisper --window --debug
    if ($LASTEXITCODE -ne 0) {
        throw "java exited with code $LASTEXITCODE"
    }
} else {
    Write-Host "Build complete."
    Write-Host "Run app with:"
    Write-Host "java -cp ""out;$classpath"" whisper.MisterWhisper --window --debug"
}
