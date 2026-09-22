[CmdletBinding()]
param(
    [Alias('bridge-config')]
    [string]$BridgeConfig,

    [Alias('monaka-config')]
    [string]$MonakaConfig,

    [Alias('run-directory')]
    [string]$RunDirectory,

    [Alias('prepare-only')]
    [switch]$PrepareOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ExpectedSourceId = 'vive-local-1'
$ExpectedDeviceId = '23:34:e4:5a:fe:39'
$ExpectedPublisherId = 'monaka-bridge-local-1'
$ExpectedTrackerId = 'altra-1'
$ExpectedPort = 29811
$ExpectedTimeoutNs = 500000000L
$ExpectedConvention = 'rh_y_up_neg_z_forward'

function Resolve-ExistingFile([string]$Path, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$Description path is empty."
    }
    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction Stop
    if (-not [IO.File]::Exists($resolved.Path)) {
        throw "$Description is not a regular file: $($resolved.Path)"
    }
    return $resolved.Path
}

function Require-Text($Value, [string]$Description) {
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        throw "$Description is missing or empty."
    }
    return $text
}

function Read-Json([string]$Path, [string]$Description) {
    try {
        return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw "$Description is not valid JSON: $Path`n$($_.Exception.Message)"
    }
}

function Write-Utf8Json([string]$Path, $Value) {
    $json = $Value | ConvertTo-Json -Depth 100
    [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Get-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return (($sha256.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
}

function Assert-MonakaConfig($Config, $Mapping, [string]$BridgeId, [switch]$Generated) {
    if ([int]$Config.version -ne 2) { throw 'Monaka config version must be 2.' }
    if ([int]$Config.port -ne $ExpectedPort) { throw "Monaka MTP port must be $ExpectedPort." }
    if ([long]$Config.timeout_ns -ne $ExpectedTimeoutNs) { throw "Monaka timeout_ns must be $ExpectedTimeoutNs." }
    if ((Require-Text $Config.space.id 'Monaka world space') -ne (Require-Text $Mapping.world_space 'Bridge mapping world_space')) {
        throw 'Monaka world space does not match the selected Bridge mapping.'
    }
    if ([long]$Config.space.revision -ne [long]$Mapping.world_revision) {
        throw 'Monaka world revision does not match the selected Bridge mapping.'
    }
    if ((Require-Text $Config.space.convention 'Monaka world convention') -ne $ExpectedConvention) {
        throw "Monaka world convention must be $ExpectedConvention."
    }

    $assignments = @($Config.assignments)
    if ($assignments.Count -ne 1) { throw 'Monaka config must contain exactly one assignment.' }
    $assignment = $assignments[0]
    if ((Require-Text $assignment.body 'Assignment body') -ne 'HIP') { throw 'The only assignment must target HIP.' }
    $fallbackProperty = $assignment.PSObject.Properties['rotationFallbackTracker']
    if ($null -eq $fallbackProperty -or $null -ne $fallbackProperty.Value) {
        throw 'HIP rotationFallbackTracker must be explicitly null.'
    }
    if ((Require-Text $assignment.mainTracker.kind 'Main tracker kind') -ne 'mtp') {
        throw 'HIP Main must be an MTP tracker.'
    }

    if ($Generated) {
        $publisherId = Require-Text $assignment.mainTracker.publisher_id 'Generated publisher_id'
        $sourceId = Require-Text $assignment.mainTracker.source_id 'Generated source_id'
        $trackerId = Require-Text $assignment.mainTracker.tracker_id 'Generated tracker_id'
        if ($publisherId -ne $BridgeId -or $sourceId -ne [string]$Mapping.source_id -or $trackerId -ne [string]$Mapping.tracker_id) {
            throw 'Generated HIP Main identity does not exactly match the selected Bridge mapping.'
        }
        if ($publisherId -ne $ExpectedPublisherId -or $sourceId -ne $ExpectedSourceId -or $trackerId -ne $ExpectedTrackerId) {
            throw "Generated HIP Main is not the intended HIL identity $ExpectedPublisherId / $ExpectedSourceId / $ExpectedTrackerId."
        }
    }

    return $assignment
}

function Get-Java17 {
    $candidates = [Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe'))
    }
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -ne $javaCommand) { $candidates.Add($javaCommand.Source) }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not [IO.File]::Exists($candidate)) { continue }
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $candidate
        $startInfo.Arguments = '-version'
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process = [Diagnostics.Process]::Start($startInfo)
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        $exitCode = $process.ExitCode
        $process.Dispose()
        $version = $standardOutput + $standardError
        if ($exitCode -eq 0 -and $version -match 'version\s+"17(?:\.|"|-)') {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    throw 'Java 17 was not found. Set JAVA_HOME to a JDK 17 installation before running the HIL helper.'
}

function Assert-PortFree([int]$Port) {
    try {
        $endpoints = @(Get-NetUDPEndpoint -LocalPort $Port -ErrorAction Stop)
    } catch [Microsoft.PowerShell.Cmdletization.Cim.CimJobException] {
        $endpoints = @()
    }
    if ($endpoints.Count -eq 0) { return }

    $owners = foreach ($endpoint in $endpoints) {
        $processName = try { (Get-Process -Id $endpoint.OwningProcess -ErrorAction Stop).ProcessName } catch { '<unavailable>' }
        [pscustomobject]@{
            LocalAddress = $endpoint.LocalAddress
            LocalPort = $endpoint.LocalPort
            PID = $endpoint.OwningProcess
            Process = $processName
        }
    }
    Write-Host "UDP port $Port is already owned by another process:"
    $owners | Format-Table -AutoSize | Out-Host
    throw "Close the process using UDP $Port manually. This helper will not kill it."
}

$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $RepositoryRoot

if ([string]::IsNullOrWhiteSpace($BridgeConfig)) {
    $BridgeConfig = Join-Path $RepositoryRoot '..\MonakaBridge\config\bridge.json'
}
if ([string]::IsNullOrWhiteSpace($MonakaConfig)) {
    $MonakaConfig = Join-Path $RepositoryRoot 'server\desktop\monaka-mtp.json'
}
$BridgeConfig = Resolve-ExistingFile $BridgeConfig 'Bridge config'
$MonakaConfig = Resolve-ExistingFile $MonakaConfig 'Monaka template config'

$bridge = Read-Json $BridgeConfig 'Bridge config'
if ([int]$bridge.version -ne 2) { throw 'Bridge config version must be 2.' }
$bridgeId = Require-Text $bridge.bridge_id 'Bridge bridge_id'
if (@('monaka', 'both') -notcontains [string]$bridge.policy) {
    throw 'Bridge policy must be monaka or both.'
}
$bridgeMappingRevision = [long]$bridge.mapping_revision
if ($bridgeMappingRevision -lt 0) { throw 'Bridge mapping_revision must be non-negative.' }

$matches = @($bridge.mappings | Where-Object {
    [string]$_.source_id -eq $ExpectedSourceId -and [string]$_.device_id -eq $ExpectedDeviceId
})
if ($matches.Count -ne 1) {
    throw "Expected exactly one Bridge mapping for $ExpectedSourceId / $ExpectedDeviceId; found $($matches.Count)."
}
$mapping = $matches[0]
if ($mapping.space_approved -ne $true) { throw 'Selected Bridge mapping space is not explicitly approved.' }
$profileName = Require-Text $mapping.profile 'Selected profile name'
$profileProperty = $bridge.profiles.PSObject.Properties[$profileName]
if ($null -eq $profileProperty) { throw "Selected Bridge profile does not exist: $profileName" }
$profile = $profileProperty.Value
if ($profile.approved -ne $true) { throw "Selected Bridge profile is not approved: $profileName" }
$profileEvidence = Require-Text $profile.evidence "Selected Bridge profile evidence ($profileName)"
$inputSpace = Require-Text $mapping.input_space 'Bridge input_space'
$inputRevision = [long]$mapping.input_revision
if ($inputRevision -lt 0) { throw 'Bridge input_revision must be non-negative.' }
$trackerId = Require-Text $mapping.tracker_id 'Bridge tracker_id'
if ($bridgeId -ne $ExpectedPublisherId -or $trackerId -ne $ExpectedTrackerId) {
    throw "Selected mapping is not the intended HIL identity $ExpectedPublisherId / $ExpectedSourceId / $ExpectedTrackerId."
}

$temporaryConfig = Read-Json $MonakaConfig 'Monaka template config'
$assignment = Assert-MonakaConfig $temporaryConfig $mapping $bridgeId
$assignment.mainTracker.publisher_id = $bridgeId
$assignment.mainTracker.source_id = [string]$mapping.source_id
$assignment.mainTracker.tracker_id = $trackerId

if ([string]::IsNullOrWhiteSpace($RunDirectory)) {
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $RunDirectory = Join-Path $RepositoryRoot ("build\steamvr-hil-$stamp-$([guid]::NewGuid().ToString('N'))")
} elseif (-not [IO.Path]::IsPathRooted($RunDirectory)) {
    $RunDirectory = Join-Path $RepositoryRoot $RunDirectory
}
$RunDirectory = [IO.Path]::GetFullPath($RunDirectory)
if (Test-Path -LiteralPath $RunDirectory) {
    throw "Run directory already exists; refusing to overwrite it: $RunDirectory"
}
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($RunDirectory)) | Out-Null
[IO.Directory]::CreateDirectory($RunDirectory) | Out-Null

$generatedConfigPath = Join-Path $RunDirectory 'monaka-mtp.json'
$mappingSnapshotPath = Join-Path $RunDirectory 'bridge-mapping.json'
$manifestPath = Join-Path $RunDirectory 'manifest.json'
Write-Utf8Json $generatedConfigPath $temporaryConfig

$reloadedConfig = Read-Json $generatedConfigPath 'Generated Monaka config'
$reloadedAssignment = Assert-MonakaConfig $reloadedConfig $mapping $bridgeId -Generated

$mappingSnapshot = [ordered]@{
    bridge_id = $bridgeId
    bridge_mapping_revision = $bridgeMappingRevision
    policy = [string]$bridge.policy
    selected_mapping = $mapping
    selected_profile_name = $profileName
    selected_profile = $profile
}
Write-Utf8Json $mappingSnapshotPath $mappingSnapshot

$gitHead = (& git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $gitHead -notmatch '^[0-9a-f]{40}$') { throw 'Could not record MonakaVR git HEAD.' }
$gitBranch = (& git branch --show-current).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not record MonakaVR git branch.' }

$manifest = [ordered]@{
    mode = if ($PrepareOnly) { 'prepare-only' } else { 'production SteamVR ROTATION_ONLY HIL' }
    hardware_verdict = 'NOT EVALUATED'
    started_utc = [DateTime]::UtcNow.ToString('o')
    completed_utc = $null
    monaka_git_head = $gitHead
    monaka_git_branch = $gitBranch
    bridge_config = $BridgeConfig
    monaka_template_config = $MonakaConfig
    generated_config = $generatedConfigPath
    generated_config_sha256 = Get-Sha256 $generatedConfigPath
    mapping_snapshot = $mappingSnapshotPath
    mapping_snapshot_sha256 = Get-Sha256 $mappingSnapshotPath
    publisher_id = [string]$reloadedAssignment.mainTracker.publisher_id
    source_id = [string]$reloadedAssignment.mainTracker.source_id
    tracker_id = [string]$reloadedAssignment.mainTracker.tracker_id
    mtp_port = [int]$reloadedConfig.port
    timeout_ns = ([long]$reloadedConfig.timeout_ns).ToString()
    bridge_mapping_revision = $bridgeMappingRevision
    input_space = $inputSpace
    input_revision = $inputRevision
    world_space = [string]$reloadedConfig.space.id
    world_revision = [long]$reloadedConfig.space.revision
    world_convention = [string]$reloadedConfig.space.convention
    profile = $profileName
    profile_evidence = $profileEvidence
    port_gate = 'NOT RUN'
    build = 'NOT RUN'
    jar_path = $null
    jar_sha256 = $null
    production_main_class = 'dev.slimevr.desktop.Main'
    runtime = 'NOT RUN'
    runtime_exit_code = $null
}
Write-Utf8Json $manifestPath $manifest

Write-Host "Prepared HIL run directory: $RunDirectory"
Write-Host "HIP Main: $($manifest.publisher_id) / $($manifest.source_id) / $($manifest.tracker_id)"
Write-Host "Input map: $inputSpace revision $inputRevision (Bridge mapping revision $bridgeMappingRevision)"
Write-Host "World: $($manifest.world_space) revision $($manifest.world_revision) / $($manifest.world_convention)"
Write-Host "Config SHA-256: $($manifest.generated_config_sha256)"

if ($PrepareOnly) {
    $manifest.completed_utc = [DateTime]::UtcNow.ToString('o')
    Write-Utf8Json $manifestPath $manifest
    Write-Host 'PREPARE ONLY complete. Production runtime and hardware/SteamVR HIL were NOT RUN.'
    exit 0
}

Assert-PortFree $ExpectedPort
$manifest.port_gate = 'PASS'
Write-Utf8Json $manifestPath $manifest

$javaExe = Get-Java17
$javaHome = Split-Path (Split-Path $javaExe -Parent) -Parent
$env:JAVA_HOME = $javaHome
$env:Path = "$(Join-Path $javaHome 'bin');$env:Path"

Write-Host 'Building production shadow JAR with Java 17...'
$savedPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    & (Join-Path $RepositoryRoot 'gradlew.bat') :server:desktop:shadowJar --no-daemon --console=plain
    $buildExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedPreference
}
if ($buildExit -ne 0) {
    $manifest.build = 'FAIL'
    $manifest.completed_utc = [DateTime]::UtcNow.ToString('o')
    Write-Utf8Json $manifestPath $manifest
    throw "Production shadowJar build failed with exit code $buildExit. Runtime was not started."
}

$jarPath = Join-Path $RepositoryRoot 'server\desktop\build\libs\slimevr.jar'
if (-not [IO.File]::Exists($jarPath)) { throw "shadowJar completed but the production JAR was not found: $jarPath" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    if ($null -eq $archive.GetEntry('dev/slimevr/desktop/Main.class')) {
        throw 'Production JAR does not contain dev.slimevr.desktop.Main.'
    }
} finally {
    $archive.Dispose()
}

$manifest.build = 'PASS'
$manifest.jar_path = [IO.Path]::GetFullPath($jarPath)
$manifest.jar_sha256 = Get-Sha256 $jarPath
$manifest.runtime = 'STARTING'
Write-Utf8Json $manifestPath $manifest

Write-Host "Starting production runtime in the foreground: $jarPath"
Write-Host 'SteamVR is not started or stopped by this helper. Type exit in the server console for normal shutdown.'
$env:SLIME_SERVER_DISABLE_INSTALLER = '1'
$runtimeExit = $null
try {
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $javaExe "-Dmonaka.mtp.config=$generatedConfigPath" -jar $jarPath run --monaka-mtp
        $runtimeExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
    }
} finally {
    $manifest.runtime_exit_code = $runtimeExit
    $manifest.runtime = if ($runtimeExit -eq 0) { 'EXITED' } elseif ($null -eq $runtimeExit) { 'INTERRUPTED' } else { 'FAILED' }
    $manifest.completed_utc = [DateTime]::UtcNow.ToString('o')
    Write-Utf8Json $manifestPath $manifest
}
if ($runtimeExit -ne 0) { throw "Production runtime exited with code $runtimeExit." }

Write-Host "Run evidence saved: $RunDirectory"
Write-Host 'Hardware/SteamVR verdict remains NOT EVALUATED; review the documented observations manually.'
