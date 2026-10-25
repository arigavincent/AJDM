<#
.SYNOPSIS
Same-host AJDM vs IDM paired comparison following docs/BENCHMARK_PROTOCOL.md.

.DESCRIPTION
Runs -Pairs adjacent pairs of one AJDM mode against IDM, alternating which tool goes first. Every trial gets a fresh
local fixture (-Fixture) or reuses a fixed external source (-Url/-Sha256/-Bytes). Both tools are timed from starting the
task to verified completion: AJDM's sidecar time covers its own SHA-256; IDM's time includes an external SHA-256 because
IDM does not verify. Each AJDM output is also rehashed independently (outside its timer) before it is admitted.
Failed or unverified attempts go to rejections.csv rather than being dropped. Outputs are kept, never deleted; the run
stops if free space would fall below -MinFreeGB.

Writes samples.csv (docs/external-benchmark-template.csv columns), rejections.csv and context.json to -OutDir, then
runs tools/summarize_external_benchmarks.py when every tool has at least ten verified samples.

.EXAMPLE
.\tools\Compare-Idm.ps1 -Fixture per-response-cap -Mode fixed -IdmVersion "6.42 Build 27" -IdmConnections 4 -OutDir C:\AJDM-bench\local-fixed

.EXAMPLE
.\tools\Compare-Idm.ps1 -Url https://cdn.example/file.iso -Sha256 <hex> -Bytes 123 -SourceId debian-netinst -Network home-wifi -Mode adaptive -IdmVersion "6.42 Build 27" -IdmConnections 8 -OutDir D:\bench\cdn-adaptive
#>
[CmdletBinding(DefaultParameterSetName = "Local")]
param(
    [Parameter(Mandatory, ParameterSetName = "Local")][string]$Fixture,
    [Parameter(ParameterSetName = "Local")][int]$Port = 8765,
    [Parameter(Mandatory, ParameterSetName = "External")][string]$Url,
    [Parameter(Mandatory, ParameterSetName = "External")][ValidatePattern("^[0-9a-fA-F]{64}$")][string]$Sha256,
    [Parameter(Mandatory, ParameterSetName = "External")][long]$Bytes,
    [Parameter(Mandatory, ParameterSetName = "External")][string]$SourceId,
    [ValidateSet("single", "fixed", "adaptive")][string]$Mode = "fixed",
    [Parameter(Mandatory)][string]$IdmVersion,
    [Parameter(Mandatory)][int]$IdmConnections,
    [Parameter(Mandatory)][string]$OutDir,
    [int]$Pairs = 10,
    [string]$HostId = $env:COMPUTERNAME.ToLowerInvariant(),
    [string]$Network = "loopback",
    [ValidateSet("warm", "cold")][string]$CachePolicy = "warm",
    [string]$IdmPath = "${env:ProgramFiles(x86)}\Internet Download Manager\IDMan.exe",
    [int]$TimeoutSeconds = 1800,
    [double]$MinFreeGB = 5,
    [switch]$SkipIdm
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$bench = Join-Path $repo "ajdm-bench\build\install\ajdm-bench\bin\ajdm-bench.bat"
if (-not (Test-Path $bench)) { throw "Build the runner first: .\gradlew.bat :ajdm-bench:installDist" }
if (-not $env:JAVA_HOME) { throw "Set JAVA_HOME to a Java 25 JDK" }
if (-not $SkipIdm -and -not (Test-Path $IdmPath)) { throw "IDM not found at $IdmPath (use -IdmPath)" }
if ($Pairs -lt 10 -and -not $SkipIdm) { Write-Warning "The protocol and importer require at least ten pairs." }
if (Test-Path $OutDir) { if (Get-ChildItem $OutDir -Force | Select-Object -First 1) { throw "OutDir must be new or empty: $OutDir" } }
New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$local = $PSCmdlet.ParameterSetName -eq "Local"
if ($local) { $SourceId = "fixture-$Fixture" }
$comparison = "$Mode-idm"
$columns = "run_id,captured_at_utc,host_id,network,source_id,comparison_id,cache_policy,sequence,mode,tool_version,bytes,sha256,seconds,received_bytes,process_cpu_seconds,peak_rss_bytes,verified"
$samples = Join-Path $OutDir "samples.csv"; $rejections = Join-Path $OutDir "rejections.csv"
Set-Content -Encoding ascii $samples $columns
Set-Content -Encoding ascii $rejections "sequence,mode,captured_at_utc,elapsed_seconds,reason"
$commit = (git -C $repo rev-parse --short HEAD).Trim()
$dirty = [bool](git -C $repo status --porcelain --untracked-files=no)
$ajdmVersion = "ajdm-" + $commit + $(if ($dirty) { "-dirty" } else { "" })

function Write-Context {
    $os = Get-CimInstance Win32_OperatingSystem; $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $disk = Get-Volume -DriveLetter $OutDir.Substring(0, 1) -ErrorAction SilentlyContinue
    $ErrorActionPreference = "Continue"
    $java = (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-Object -First 1).ToString()
    $ErrorActionPreference = "Stop"
    [ordered]@{
        protocol = "docs/BENCHMARK_PROTOCOL.md"; started_utc = (Get-Date).ToUniversalTime().ToString("o")
        host_id = $HostId; network = $Network; source_id = $SourceId; comparison_id = $comparison; cache_policy = $CachePolicy
        os = "$($os.Caption) $($os.Version)"; cpu = $cpu.Name; logical_processors = $cpu.NumberOfLogicalProcessors
        ram_gb = [math]::Round($os.TotalVisibleMemorySize / 1MB, 1); output_volume = $OutDir.Substring(0, 2)
        output_filesystem = $disk.FileSystemType; java = $java; ajdm_version = $ajdmVersion
        ajdm_mode = $Mode; ajdm_connections = $(if ($Mode -eq "single") { 1 } else { 4 })
        idm_version = $IdmVersion; idm_connections = $IdmConnections; idm_path = $(if ($SkipIdm) { $null } else { $IdmPath })
        pairs = $Pairs; source = $(if ($local) { "local fixture '$Fixture', fresh per trial" } else { "external, fixed bytes and SHA-256" })
        timing = "start of task to verified completion; AJDM excludes JVM startup and includes its own SHA-256; IDM includes command hand-off and an external SHA-256"
        notes = "Operator: record antivirus, proxy, power plan and any IDM settings changed from defaults here."
    } | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $OutDir "context.json")
}

function Stop-Tree([int]$id) {
    Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" | ForEach-Object { Stop-Tree $_.ProcessId }
    Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
}

function Start-Fixture {
    $log = Join-Path $OutDir ".fixture-$([guid]::NewGuid().ToString('N')).out"
    $proc = Start-Process -FilePath $bench -ArgumentList "serve-benchmark", $Fixture, $Port -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $log) -and (Select-String -Path $log -Pattern "^SHA-256: " -Quiet)) {
            $text = Get-Content $log
            return @{ Process = $proc; Url = ($text -match "^Source: ")[0].Substring(8); Bytes = [long]($text -match "^Bytes: ")[0].Substring(7); Sha256 = ($text -match "^SHA-256: ")[0].Substring(9) }
        }
        if ($proc.HasExited) { break }
        Start-Sleep -Milliseconds 100
    }
    Stop-Tree $proc.Id; throw "Benchmark fixture did not start (is port $Port free?)"
}

function Assert-Space([long]$need) {
    $free = (Get-PSDrive $OutDir.Substring(0, 1)).Free
    if ($free - $need -lt $MinFreeGB * 1GB) { throw "Stopping: free space would fall below $MinFreeGB GB" }
}

function Blank($value) { if ($null -eq $value -or $value -eq "" -or [double]$value -lt 0) { "" } else { $value } }

function Add-Sample($sequence, $mode, $version, $source, $seconds, $received, $cpu, $rss) {
    $row = @("$comparison-$sequence-$mode", (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"), $HostId, $Network, $SourceId, $comparison,
        $CachePolicy, $sequence, $mode, $version, $source.Bytes, $source.Sha256.ToLowerInvariant(), $seconds, (Blank $received), (Blank $cpu), (Blank $rss), "true")
    Add-Content -Encoding ascii $samples ($row -join ",")
}

function Add-Rejection($sequence, $mode, $elapsed, $reason) {
    Add-Content -Encoding ascii $rejections (@($sequence, $mode, (Get-Date).ToUniversalTime().ToString("o"), $elapsed, ('"' + ($reason -replace '"', "'") + '"')) -join ",")
    Write-Warning "Rejected $mode #$sequence after $elapsed s: $reason"
}

function Invoke-Ajdm($sequence, $source) {
    $target = Join-Path $OutDir ("ajdm-$Mode-{0:D3}.bin" -f $sequence)
    # Windows PowerShell turns native stderr into terminating errors under "Stop"; the exit code decides instead.
    $ErrorActionPreference = "Continue"
    $output = & $bench --state (Join-Path $OutDir "ajdm-state.db") benchmark-download $Mode $source.Url $target $source.Sha256 2>&1
    $ErrorActionPreference = "Stop"
    $sidecar = "$target.benchmark.csv"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $sidecar)) { Add-Rejection $sequence $Mode "" "AJDM exit $LASTEXITCODE $($output | Select-Object -Last 1)"; return }
    $m = Import-Csv $sidecar
    $hash = (Get-FileHash -Algorithm SHA256 $target).Hash
    if ($hash -ne $source.Sha256.ToUpperInvariant() -or (Get-Item $target).Length -ne $source.Bytes) { Add-Rejection $sequence $Mode $m.seconds "independent rehash or size mismatch"; return }
    Add-Sample $sequence $Mode $ajdmVersion $source $m.seconds $m.received_bytes $m.process_cpu_seconds $m.sampled_rss_peak_bytes
    Write-Host ("  AJDM {0,-8} #{1,-3} {2,8:N3} s" -f $Mode, $sequence, [double]$m.seconds)
}

function Invoke-Idm($sequence, $source) {
    $name = "idm-{0:D3}.bin" -f $sequence; $target = Join-Path $OutDir $name
    $clock = [Diagnostics.Stopwatch]::StartNew()
    Start-Process -FilePath $IdmPath -ArgumentList "/d", "`"$($source.Url)`"", "/p", "`"$OutDir`"", "/f", $name, "/n" | Out-Null
    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if ((Test-Path -LiteralPath $target) -and (Get-Item -LiteralPath $target).Length -eq $source.Bytes) {
            try { $stream = [IO.File]::Open($target, "Open", "Read", "None"); $stream.Close(); break } catch { }
        }
        Start-Sleep -Milliseconds 20
    }
    if ($clock.Elapsed.TotalSeconds -ge $TimeoutSeconds) { Add-Rejection $sequence "idm" ([math]::Round($clock.Elapsed.TotalSeconds, 3)) "no complete file before timeout"; return }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash; $clock.Stop()
    $seconds = [math]::Round($clock.Elapsed.TotalSeconds, 6)
    if ($hash -ne $source.Sha256.ToUpperInvariant()) { Add-Rejection $sequence "idm" $seconds "SHA-256 mismatch"; return }
    Add-Sample $sequence "idm" ("idm-" + ($IdmVersion -replace '[^0-9A-Za-z.]+', '-')) $source $seconds "" "" ""
    Write-Host ("  IDM           #{0,-3} {1,8:N3} s" -f $sequence, $seconds)
}

Write-Context
$sequence = 0
for ($pair = 1; $pair -le $Pairs; $pair++) {
    $order = if ($pair % 2 -eq 1) { @("ajdm", "idm") } else { @("idm", "ajdm") }
    Write-Host "Pair $pair/$Pairs ($($order -join ' then '))"
    foreach ($tool in $order) {
        $sequence++
        if ($tool -eq "idm" -and $SkipIdm) { continue }
        $server = $null
        try {
            if ($local) { $server = Start-Fixture; $source = $server } else { $source = @{ Url = $Url; Bytes = $Bytes; Sha256 = $Sha256 } }
            Assert-Space $source.Bytes
            if ($tool -eq "ajdm") { Invoke-Ajdm $sequence $source } else { Invoke-Idm $sequence $source }
        } finally { if ($server) { Stop-Tree $server.Process.Id; Start-Sleep -Milliseconds 300 } }
    }
}

$rows = @(Import-Csv $samples)
$counts = $rows | Group-Object mode | ForEach-Object { "$($_.Name)=$($_.Count)" }
Write-Host "Verified samples: $($counts -join ', ')  Rejections: $(@(Import-Csv $rejections).Count)"
if (-not $SkipIdm -and @($rows | Group-Object mode | Where-Object Count -ge 10).Count -eq 2) {
    py -3 (Join-Path $repo "tools\summarize_external_benchmarks.py") $samples --output (Join-Path $OutDir "report")
    Write-Host "Report: $(Join-Path $OutDir 'report')"
} else { Write-Host "Not summarized: the importer needs at least ten verified samples per tool." }
