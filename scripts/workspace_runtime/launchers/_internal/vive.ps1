param([switch]$Check)

$ErrorActionPreference = "Stop"
if ($args.Count) { throw "Unknown arguments: $($args -join ' ')" }
$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\ViveUltimateTrackerBridge'))
. (Join-Path $PSScriptRoot 'vive_process.ps1')

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}


$ConfigPath = Resolve-RepoPath "config\vut-service.conf"
$JournalPath = ""

$requirements = [System.Collections.Generic.List[string]]::new()
if (-not $JournalPath) {
    if ($env:LOCALAPPDATA) {
        $JournalPath = Join-Path $env:LOCALAPPDATA "MonakaVR\ViveUltimateTrackerBridge\vut-service.revision.log"
    } else {
        $requirements.Add('LOCALAPPDATA is unavailable; configure the user environment before launch.')
    }
}
if ($JournalPath) { $JournalPath = Resolve-RepoPath $JournalPath }
if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    $requirements.Add("Missing configuration: $ConfigPath. Prepare it from config\vut-service.example.conf and edit source/map values.")
} else {
    $configText = Get-Content -LiteralPath $ConfigPath -Raw
    if ($configText -match "vive-installation-example" -or
        $configText -match "23:32:42:b7:82:d3" -or
        $configText -match "24:32:42:b7:82:d3") {
        $requirements.Add("Configuration still contains example placeholder values: $ConfigPath")
    }
}
$BuildConfig = "Release"
$Port = 29810
$Diagnostics = $false
$buildDir = Join-Path $RepoRoot "Build\runtime"
$exe = Join-Path $buildDir "$BuildConfig\vut_service.exe"
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    $requirements.Add("Missing executable: $exe. Run Build_Runtime.cmd explicitly.")
}
if ($requirements.Count) {
    throw ("Runtime prerequisites are not ready:`n - " + ($requirements -join "`n - "))
}
if ($Check) {
    [pscustomobject]@{
        status = 'FILES_PRESENT'; executable = $exe; config = $ConfigPath; journal = $JournalPath
        port = $Port; diagnostics = [bool]$Diagnostics; process_started = $false
        configuration_validated = $false; runtime_readiness_verified = $false; gui_available = $false
        build_command = Join-Path $RepoRoot 'Build_Runtime.cmd'
    } | ConvertTo-Json
    exit 0
}
$running = @(Get-CimInstance Win32_Process -Filter "Name='vut_service.exe'" -ErrorAction Stop)
$matching = Get-ViveMatchingProcess -Processes $running -Executable $exe -ConfigPath $ConfigPath -JournalPath $JournalPath -Port $Port -Diagnostics ([bool]$Diagnostics)
if ($null -ne $matching) {
    Write-Host "Matching vut_service process exists (PID $($matching.ProcessId)); binary path, config, journal and options match."
    Write-Host 'This is process identity confirmation, not a hardware/stream health check. No rebuild was performed.'
    exit 0
}

# Only the explicit repository build command updates this runtime.
$journalDir = Split-Path -Parent $JournalPath
if ($journalDir -and -not (Test-Path -LiteralPath $journalDir)) {
    New-Item -ItemType Directory -Path $journalDir -Force | Out-Null
}

Write-Host ""
Write-Host "Starting VIVE Ultimate Tracker backend service..."
Write-Host "  exe:     $exe"
Write-Host "  config:  $ConfigPath"
Write-Host "  journal: $JournalPath"
Write-Host "  target:  127.0.0.1:$Port"
Write-Host ""
Write-Host "Close VIVE Hub / VIVE Streaming Hub before using the official dongle."
Write-Host "Ctrl+C stops the service."
Write-Host ""

$serviceArguments = @($ConfigPath, $JournalPath, $Port)
if ($Diagnostics) { $serviceArguments += '--diagnostics' }
& $exe @serviceArguments
exit $LASTEXITCODE
