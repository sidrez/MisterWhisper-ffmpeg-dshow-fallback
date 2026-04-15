param(
    [switch]$Run
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$runtimeDir = Join-Path $repoRoot "runtime\win32-x86-64"
$outDir = Join-Path $repoRoot "out"
$outRuntimeDir = Join-Path $outDir "win32-x86-64"

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
Copy-Item -LiteralPath (Join-Path $runtimeDir "*.dll") -Destination $outRuntimeDir -Force

$sources = Get-ChildItem -Path "src" -Recurse -Filter "*.java" -File | ForEach-Object { $_.FullName }
$classpath = "lib\jna.jar;lib\jnativehook-2.2.2.jar;lib\win32-x86-64.jar"

Write-Host "Compiling Java sources..."
javac -encoding UTF-8 -cp $classpath -d $outDir $sources

if ($Run) {
    Write-Host "Starting MisterWhisper..."
    & java -cp "out;$classpath" whisper.MisterWhisper --window --debug
} else {
    Write-Host "Build complete."
    Write-Host "Run app with:"
    Write-Host "java -cp ""out;$classpath"" whisper.MisterWhisper --window --debug"
}
