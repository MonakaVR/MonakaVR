$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot = Join-Path $root ("build\steamvr-hil-helper-test-$([guid]::NewGuid().ToString('N'))")
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$bridgePath = Join-Path $testRoot 'bridge.json'
$monakaPath = Join-Path $testRoot 'monaka-template.json'
$runPath = Join-Path $testRoot 'run'
$helper = Join-Path $PSScriptRoot 'run_rotation_only_steamvr_hil.ps1'
$utf8 = [Text.UTF8Encoding]::new($false)

function Write-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 100) + [Environment]::NewLine), $utf8)
}

function Copy-JsonObject($Value) {
    return $Value | ConvertTo-Json -Depth 100 | ConvertFrom-Json
}

function Assert-HelperFails([string[]]$Arguments, [string]$LogPath, [string]$FailureMessage) {
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $helper @Arguments *> $LogPath
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
    }
    if ($exitCode -eq 0) { throw $FailureMessage }
}

$bridge = [ordered]@{
    version = 2
    bridge_id = 'monaka-bridge-local-1'
    mapping_revision = 81
    policy = 'monaka'
    profiles = [ordered]@{
        'vive-test' = [ordered]@{
            convention = 'vut-native-v1'
            position_axes = @(1, 2, 3)
            quaternion_axes = @(-1, -2, 3, 4)
            approved = $true
            angular_space_verified = $false
            evidence = 'Synthetic helper test evidence; not hardware evidence'
        }
    }
    mappings = @([ordered]@{
        source_id = 'vive-local-1'
        device_id = '23:34:e4:5a:fe:39'
        tracker_id = 'altra-1'
        profile = 'vive-test'
        space_approved = $true
        input_space = 'tracker-1-map'
        input_revision = 76
        world_space = 'monaka-world-local'
        world_revision = 0
    })
}
$monaka = [ordered]@{
    version = 2
    port = 29811
    timeout_ns = '500000000'
    space = [ordered]@{ id = 'monaka-world-local'; revision = 0; convention = 'rh_y_up_neg_z_forward' }
    assignments = @([ordered]@{
        body = 'HIP'
        mainTracker = [ordered]@{ kind = 'mtp'; publisher_id = 'old'; source_id = 'old'; tracker_id = 'altra-0' }
        rotationFallbackTracker = $null
    })
}
Write-Json $bridgePath $bridge
Write-Json $monakaPath $monaka
$originalTemplateHash = (Get-FileHash -LiteralPath $monakaPath -Algorithm SHA256).Hash

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $helper -BridgeConfig $bridgePath -MonakaConfig $monakaPath -RunDirectory $runPath -PrepareOnly
if ($LASTEXITCODE -ne 0) { throw "Prepare-only helper failed with exit code $LASTEXITCODE." }

$generated = Get-Content -LiteralPath (Join-Path $runPath 'monaka-mtp.json') -Raw | ConvertFrom-Json
$manifest = Get-Content -LiteralPath (Join-Path $runPath 'manifest.json') -Raw | ConvertFrom-Json
$snapshot = Get-Content -LiteralPath (Join-Path $runPath 'bridge-mapping.json') -Raw | ConvertFrom-Json
$main = @($generated.assignments)[0].mainTracker
if ($main.publisher_id -ne 'monaka-bridge-local-1' -or $main.source_id -ne 'vive-local-1' -or $main.tracker_id -ne 'altra-1') {
    throw 'Generated identity did not replace the stale altra-0 template with the selected Bridge mapping.'
}
if ($manifest.input_revision -ne 76 -or $manifest.bridge_mapping_revision -ne 81 -or $manifest.hardware_verdict -ne 'NOT EVALUATED') {
    throw 'Manifest did not preserve the dynamic revisions or NOT EVALUATED verdict.'
}
if ($snapshot.selected_mapping.tracker_id -ne 'altra-1' -or $snapshot.selected_profile.evidence -notmatch 'Synthetic helper test') {
    throw 'Selected mapping snapshot is incomplete.'
}
if ((Get-FileHash -LiteralPath $monakaPath -Algorithm SHA256).Hash -ne $originalTemplateHash) {
    throw 'Committed/template Monaka config was modified.'
}
if ($manifest.generated_config_sha256 -ne (Get-FileHash -LiteralPath (Join-Path $runPath 'monaka-mtp.json') -Algorithm SHA256).Hash.ToLowerInvariant()) {
    throw 'Manifest config SHA-256 does not match the generated config.'
}

Assert-HelperFails @('-BridgeConfig', $bridgePath, '-MonakaConfig', $monakaPath, '-RunDirectory', $runPath, '-PrepareOnly') `
    (Join-Path $testRoot 'overwrite-refusal.log') 'Helper overwrote an existing run directory.'

$noMapping = Copy-JsonObject $bridge
$noMapping.mappings = @()
$noMappingPath = Join-Path $testRoot 'bridge-no-mapping.json'
Write-Json $noMappingPath $noMapping
Assert-HelperFails @('-BridgeConfig', $noMappingPath, '-MonakaConfig', $monakaPath, '-RunDirectory', (Join-Path $testRoot 'no-mapping-run'), '-PrepareOnly') `
    (Join-Path $testRoot 'no-mapping.log') 'Helper accepted zero exact mappings.'

$duplicateMapping = Copy-JsonObject $bridge
$duplicateMapping.mappings = @($bridge.mappings[0], $bridge.mappings[0])
$duplicateMappingPath = Join-Path $testRoot 'bridge-duplicate-mapping.json'
Write-Json $duplicateMappingPath $duplicateMapping
Assert-HelperFails @('-BridgeConfig', $duplicateMappingPath, '-MonakaConfig', $monakaPath, '-RunDirectory', (Join-Path $testRoot 'duplicate-run'), '-PrepareOnly') `
    (Join-Path $testRoot 'duplicate.log') 'Helper accepted multiple exact mappings.'

Write-Host "PASS rotation-only SteamVR HIL helper prepare-only tests: $testRoot"
