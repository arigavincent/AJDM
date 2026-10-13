param([string]$ImagePath = 'build/windows/image/AJDM')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
Set-Location $root
$image = (Resolve-Path $ImagePath).Path
$profile = Join-Path $env:TEMP ('ajdm-package-' + [Guid]::NewGuid().ToString('N'))
$stdout = "$profile-stdout.log"; $stderr = "$profile-stderr.log"
$process = Start-Process "$image/AJDM.exe" -ArgumentList @('--profile',"`"$profile`"",'--close-after','20') -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
try {
    $ready = $false; $window = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        $process.Refresh()
        if ($process.HasExited) { break }
        # jpackage may restart itself to configure its DLL environment; the JavaFX window belongs to that child.
        $visible = Get-Process -Name AJDM -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq "$image/AJDM.exe".Replace('/','\') -and $_.MainWindowTitle -like 'AJDM*' }
        if ($visible) { $window = $true }
        if (!$ready -and (Test-Path "$profile/core.endpoint")) {
            & "$image/AJDM-cli.exe" --socket "$profile/core.endpoint" ping
            if ($LASTEXITCODE -eq 0) { $ready = $true }
        }
        if ($ready -and $window) { break }
        Start-Sleep -Milliseconds 250
    }
    if (!$ready -or !$window) { throw 'Packaged core and JavaFX window did not become ready.' }
    & "$image/AJDM-cli.exe" --socket "$profile/core.endpoint" list --json
    if ($LASTEXITCODE -ne 0) { throw 'Packaged CLI could not query the core.' }
    if (!$process.WaitForExit(45000)) { throw 'Preview did not close within deadline.' }
    if ($process.ExitCode -ne 0) { throw "Packaged UI exited with $($process.ExitCode)" }
    if (Test-Path "$profile/core.endpoint") { throw 'Owned preview core did not shut down cleanly.' }
    @{javafxWindow=$window;corePing=$ready;packagedCli=$true;gracefulShutdown=$true;platform=[System.Environment]::OSVersion.VersionString} | ConvertTo-Json | Set-Content 'build/windows/package-smoke.json'
} finally {
    if (!$process.HasExited) { Stop-Process -Id $process.Id }
    if (Test-Path $stderr) { Get-Content $stderr }
    if (Test-Path "$profile/core.log") { Get-Content "$profile/core.log" }
}
