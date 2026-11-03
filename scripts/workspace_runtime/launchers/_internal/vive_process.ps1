# Read-only inspection helpers. No process is started, stopped or altered here.
function Split-ViveWindowsCommandLine {
    param([Parameter(Mandatory=$true)][string]$CommandLine)
    if (-not ('ViveLauncher.CommandLine' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace ViveLauncher {
    public static class CommandLine {
        [DllImport("shell32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
        private static extern IntPtr CommandLineToArgvW(string text, out int count);
        [DllImport("kernel32.dll")]
        private static extern IntPtr LocalFree(IntPtr memory);
        public static string[] Split(string text) {
            int count;
            IntPtr memory = CommandLineToArgvW(text, out count);
            if (memory == IntPtr.Zero) throw new InvalidOperationException("Cannot parse process command line");
            try {
                var result = new string[count];
                for (int i = 0; i < count; ++i)
                    result[i] = Marshal.PtrToStringUni(Marshal.ReadIntPtr(memory, i * IntPtr.Size));
                return result;
            } finally { LocalFree(memory); }
        }
    }
}
'@
    }
    return [ViveLauncher.CommandLine]::Split($CommandLine)
}

function Test-ViveSameAbsolutePath {
    param([string]$Left, [string]$Right)
    if ([string]::IsNullOrWhiteSpace($Left) -or [string]::IsNullOrWhiteSpace($Right)) { return $false }
    # The other process's current directory is unknown: relative arguments fail closed.
    # IsPathRooted alone also accepts C:relative and \root-relative on Windows.
    if ($Left -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\]+\\[^\\]+)' -or
        $Right -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\]+\\[^\\]+)') { return $false }
    return [string]::Equals([IO.Path]::GetFullPath($Left), [IO.Path]::GetFullPath($Right), [StringComparison]::OrdinalIgnoreCase)
}

function Get-ViveMatchingProcess {
    param([object[]]$Processes, [string]$Executable, [string]$ConfigPath, [string]$JournalPath,
          [int]$Port, [bool]$Diagnostics)
    if ($Processes.Count -eq 0) { return $null }
    if ($Processes.Count -ne 1) { throw 'Multiple vut_service processes exist; refusing to claim a matching service.' }
    $candidate = $Processes[0]
    $matchesRequest = $false
    try {
        if ((Test-ViveSameAbsolutePath $candidate.ExecutablePath $Executable) -and $candidate.CommandLine) {
            $arguments = @(Split-ViveWindowsCommandLine $candidate.CommandLine)
            if ($arguments.Count -ge 3 -and
                (Test-ViveSameAbsolutePath $arguments[0] $Executable) -and
                (Test-ViveSameAbsolutePath $arguments[1] $ConfigPath) -and
                (Test-ViveSameAbsolutePath $arguments[2] $JournalPath)) {
                $actualPort = 29810
                $hasPort = $false
                $actualDiagnostics = $false
                $valid = $true
                for ($index = 3; $index -lt $arguments.Count; ++$index) {
                    $argument = $arguments[$index]
                    if ($argument -ceq '--diagnostics' -and -not $actualDiagnostics) { $actualDiagnostics = $true }
                    elseif (-not $hasPort -and $argument -cmatch '^[0-9]+$') {
                        $actualPort = [int]$argument; $hasPort = $true
                    } else { $valid = $false }
                }
                $matchesRequest = $valid -and $actualPort -eq $Port -and $actualDiagnostics -eq $Diagnostics
            }
        }
    } catch { $matchesRequest = $false }
    if (-not $matchesRequest) {
        throw "Existing vut_service PID $($candidate.ProcessId) differs from the requested binary/config/journal/options, or cannot be inspected. No process was changed."
    }
    return $candidate
}
