param([bool]$RequireTrusted = $false)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
Set-Location $root
$work = Join-Path $root 'build/windows'
$sourceDir = if ($RequireTrusted) { "$work/signed-installer" } else { "$work/installer" }
$installer = @(Get-ChildItem "$sourceDir/*.exe")
if ($installer.Count -ne 1) { throw 'Expected exactly one installer.' }
$signature = Get-AuthenticodeSignature $installer[0].FullName
if ($RequireTrusted -and ($signature.Status -ne 'Valid' -or !$signature.TimeStamperCertificate -or $signature.SignerCertificate.Subject -eq $signature.SignerCertificate.Issuer)) { throw 'Trusted timestamped installer signature missing.' }
$suffix = if ($RequireTrusted) { 'signed' } else { 'unsigned' }
$out = Join-Path $root 'dist/windows-v1'
New-Item -ItemType Directory $out -Force | Out-Null
$name = "AJDM-1.0.0-preview.1-windows-x64-$suffix.exe"
Copy-Item $installer[0].FullName "$out/$name"
$hash = (Get-FileHash "$out/$name" -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $name" | Set-Content "$out/SHA256SUMS.txt"
@{file=$name;sha256=$hash;signatureStatus=$signature.Status.ToString();publiclyTrusted=$RequireTrusted;publisher=$(if ($signature.SignerCertificate) {$signature.SignerCertificate.Subject} else {$null});version='1.0.0-preview.1';architecture='x64';purpose='Windows testing preview; not full PRD release acceptance'} | ConvertTo-Json | Set-Content "$out/build-status.json"
Copy-Item 'docs/WINDOWS_PREVIEW.md' "$out/README-WINDOWS.md"
Copy-Item "$work/package-smoke.json" "$out/package-smoke.json"
# Exercise the actual installer silently, then start its installed application.
$install = Start-Process "$out/$name" -ArgumentList @('/quiet','/norestart') -PassThru -Wait
if ($install.ExitCode -notin @(0,3010)) { throw "Installer returned $($install.ExitCode)" }
$installed = @("$env:LOCALAPPDATA/AJDM-v1-preview/AJDM.exe", "$env:LOCALAPPDATA/Programs/AJDM-v1-preview/AJDM.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (!$installed) { throw 'Installed executable not found in expected per-user locations.' }
& "$PSScriptRoot/Test-WindowsPackage.ps1" -ImagePath (Split-Path $installed)
Copy-Item "$work/package-smoke.json" "$out/installed-smoke.json"
