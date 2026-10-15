param(
    [Parameter(Mandatory = $true)][string]$FrozenBridgeSource,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$allowedBuild = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build')) + [IO.Path]::DirectorySeparatorChar
$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $resolvedOutput.StartsWith($allowedBuild, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Legacy harness output must stay inside this GUI repository build directory.'
}
$sourceRoot = (Resolve-Path -LiteralPath $FrozenBridgeSource).Path
$head = & git -C $sourceRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $head -ne '5fbf2a9a39450e10d4362c440bbecf2c286a4833') {
    throw 'Provide the unchanged frozen 5fbf2a9 Bridge source.'
}
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
$frameworkRoot = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$compileArguments = @(
    '/nologo', '/target:exe', '/main:LegacyTraySave',
    ('/out:' + (Join-Path $resolvedOutput 'legacy-tray-save.exe')),
    '/reference:System.dll', '/reference:System.Core.dll',
    '/reference:System.Web.Extensions.dll', '/reference:System.Drawing.dll',
    '/reference:System.Windows.Forms.dll',
    ('/reference:' + (Join-Path $frameworkRoot 'WPF/WindowsBase.dll')),
    ('/reference:' + (Join-Path $frameworkRoot 'WPF/PresentationCore.dll')),
    ('/reference:' + (Join-Path $frameworkRoot 'WPF/PresentationFramework.dll')),
    ('/reference:' + (Join-Path $frameworkRoot 'System.Xaml.dll')),
    (Join-Path $sourceRoot 'ui/steamvr_control_wpf.cs'),
    (Join-Path $sourceRoot 'ui/steamvr_control_tray.cs'),
    (Join-Path $sourceRoot 'ui/control_models.cs'),
    (Join-Path $PSScriptRoot 'LegacyTraySave.cs')
)
& (Join-Path $frameworkRoot 'csc.exe') @compileArguments
if ($LASTEXITCODE -ne 0) { throw 'Legacy SaveRoute harness compile failed.' }
