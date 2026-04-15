param(
    [string]$ReleasePath = "C:\Users\ar.sitdikov\AppData\Local\Temp\ABBYY\MisterWhisper-1.3-windows-cuda"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $repoRoot "runtime\win32-x86-64"

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

$requiredDlls = @(
    "whisper.dll",
    "ggml.dll",
    "ggml-base.dll",
    "ggml-cpu.dll",
    "ggml-cuda.dll",
    "cudart64_110.dll",
    "cublas64_11.dll",
    "cublasLt64_11.dll"
)

Write-Host "Bootstrap runtime from: $ReleasePath"
Write-Host "Target runtime folder : $runtimeDir"

$missing = @()
foreach ($dll in $requiredDlls) {
    $src = Join-Path $ReleasePath $dll
    if (-not (Test-Path $src)) {
        $missing += $dll
        continue
    }
    Copy-Item -LiteralPath $src -Destination $runtimeDir -Force
    Write-Host "Copied $dll"
}

if ($missing.Count -gt 0) {
    Write-Error "Missing in release folder: $($missing -join ', ')"
}

Write-Host "Runtime bootstrap complete."
