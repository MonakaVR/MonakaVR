[CmdletBinding()]
param(
    [ValidateSet('Quick', 'Full')]
    [string]$Mode = 'Quick',
    [string]$ResultsDir = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$StartedUtc = (Get-Date).ToUniversalTime()
$RunId = $StartedUtc.ToString('yyyyMMdd-HHmmss')
if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $RepoRoot ("build/revision-test-results/{0}-{1}" -f $RunId, $Mode.ToLowerInvariant())
}
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

$StepResults = [System.Collections.Generic.List[object]]::new()
$FailedStep = $null

function Invoke-RevisionStep {
    param([string]$Name, [string]$FilePath, [string[]]$Arguments)

    $logPath = Join-Path $ResultsDir ((($Name -replace '[^A-Za-z0-9_.-]', '_')) + '.log')
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $global:LASTEXITCODE = 0
    $exitCode = 0
    $previousErrorActionPreference = $ErrorActionPreference
    Push-Location $RepoRoot
    try {
        $ErrorActionPreference = 'Continue'
        & $FilePath @Arguments *> $logPath
        if ($null -ne $LASTEXITCODE) { $exitCode = [int]$LASTEXITCODE }
    }
    catch {
        ($_ | Out-String) | Add-Content -Path $logPath
        $exitCode = 1
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
        $timer.Stop()
    }

    $StepResults.Add([pscustomobject]@{
        name = $Name
        result = $(if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' })
        exit_code = $exitCode
        duration_ms = $timer.ElapsedMilliseconds
        log = $logPath
    })
    return ($exitCode -eq 0)
}

$Gradle = Join-Path $RepoRoot 'gradlew.bat'
$Steps = @(
    @{ Name='release-tooling'; File='python'; Args=@('scripts/test_release_v2.py') },
    @{ Name='release-tooling-optimized'; File='python'; Args=@('-O','scripts/test_release_v2.py') },
    @{ Name='protocol-v2-kit'; File='python'; Args=@('scripts/verify_protocol_v2.py') }
)

if ($Mode -eq 'Quick') {
    $Steps += @{
        Name='architecture-revision-quick'
        File=$Gradle
        Args=@(
            ':server:core:test',
            '--tests','dev.monaka.tracking.ArchitectureRevisionTests',
            '--tests','dev.monaka.tracking.MonakaRuntimeTests',
            '--tests','dev.monaka.tracking.ConstraintPipelineTests',
            '--tests','dev.monaka.tracking.ConstraintResolverTests',
            '--tests','dev.monaka.tracking.SlimeTrackerPoseObservationAdapterTests',
            '--tests','dev.monaka.tracking.revision.*',
            '--no-daemon'
        )
    }
}
else {
    $Steps += @{
        Name='software-regression'
        File=$Gradle
        Args=@(':server:core:test',':server:desktop:test',':server:desktop:shadowJar','--no-daemon')
    }
    $Steps += @{
        Name='mtp-process-e2e'
        File=$Gradle
        Args=@(':server:desktop:mtpProcessE2E', ('-PmonakaE2EOutput=' + (Join-Path $ResultsDir 'mtp-process-e2e')), '--no-daemon', '--console=plain')
    }
}

foreach ($step in $Steps) {
    if (-not (Invoke-RevisionStep -Name $step.Name -FilePath $step.File -Arguments $step.Args)) {
        $FailedStep = $step.Name
        break
    }
}

$head = ''
try { $head = ((& git -C $RepoRoot rev-parse HEAD 2>$null) | Out-String).Trim() } catch { }
$result = if ($null -eq $FailedStep) { 'PASS' } else { 'FAIL' }
$summary = [ordered]@{
    repo = 'MonakaVR/MonakaVR'
    head = $head
    mode = $Mode
    result = $result
    failed_step = $FailedStep
    started_utc = $StartedUtc.ToString('o')
    ended_utc = (Get-Date).ToUniversalTime().ToString('o')
    steamvr_runtime = 'NOT RUN'
    pico_hardware = 'NOT RUN'
    vive_hardware = 'NOT RUN'
    hmd_hardware = 'NOT RUN'
    release_evidence = 'NOT RUN - run scripts/release_v2.py separately for source-bound v2 evidence'
    steps = $StepResults
}
$summaryPath = Join-Path $ResultsDir 'summary.json'
$e2eResult = Join-Path $ResultsDir 'mtp-process-e2e/result.json'
if (Test-Path -LiteralPath $e2eResult) {
    $summary['mtp_process_e2e'] = Get-Content -LiteralPath $e2eResult -Raw | ConvertFrom-Json
    if ($summary['mtp_process_e2e'].result -eq 'FAIL') {
        Write-Host ('FAIL mtp-process-e2e stopped_at=' + $summary['mtp_process_e2e'].failed_stage)
    }
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $summaryPath -Encoding UTF8
Write-Host ("RESULT={0} repo=MonakaVR mode={1} failed_step={2} summary={3}" -f $result, $Mode, $FailedStep, $summaryPath)
if ($result -eq 'FAIL') { exit 1 }
