param([switch]$Check)

$ErrorActionPreference = 'Stop'
if ($args.Count) { throw "Unknown arguments: $($args -join ' ')" }
$entry = Join-Path $PSScriptRoot '..\..\ViveUltimateTrackerBridge\scripts\start_runtime.ps1'
if (-not (Test-Path -LiteralPath $entry -PathType Leaf)) {
    throw "Missing VIVE Ultimate Tracker runtime launcher: $entry"
}
& $entry -Check:$Check
exit $LASTEXITCODE
