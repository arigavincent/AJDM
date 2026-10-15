param([ValidateSet('Image','Installer')][string]$Stage = 'Image')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path "$PSScriptRoot/../..").Path
Set-Location $root
$work = Join-Path $root 'build/windows'
$inputDir = Join-Path $work 'input'
$image = Join-Path $work 'image/AJDM'
function Run([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File failed with exit $LASTEXITCODE" }
}
if ($Stage -eq 'Image') {
    if (Test-Path $work) { throw 'Use a fresh build/windows directory for packaging.' }
    New-Item -ItemType Directory -Path $inputDir,"$work/downloads","$inputDir/tools","$inputDir/licenses","$work/signing-input" -Force | Out-Null
    Run './gradlew.bat' @('--no-daemon','--console=plain',':ajdm-ui:installDist',':ajdm-cli:installDist')
    foreach ($lib in @('ajdm-ui/build/install/ajdm-ui/lib','ajdm-cli/build/install/ajdm/lib')) {
        Get-ChildItem "$lib/*.jar" | ForEach-Object {
            $dest = Join-Path $inputDir $_.Name
            if ((Test-Path $dest) -and (Get-FileHash $dest).Hash -ne (Get-FileHash $_.FullName).Hash) { throw 'Conflicting library' }
            Copy-Item $_.FullName $dest -Force
        }
    }
    $lock = Get-Content "$PSScriptRoot/tools-lock.json" -Raw | ConvertFrom-Json
    foreach ($entry in @(@('ytDlp','yt-dlp.exe'),@('node','node.zip'),@('ffmpeg','ffmpeg.zip'))) {
        $spec = $lock.($entry[0]); $dest = "$work/downloads/$($entry[1])"
        Invoke-WebRequest $spec.url -OutFile $dest
        if ((Get-FileHash $dest -Algorithm SHA256).Hash.ToLowerInvariant() -ne $spec.sha256) { throw "Checksum mismatch: $($entry[0])" }
    }
    Copy-Item "$work/downloads/yt-dlp.exe" "$inputDir/tools/yt-dlp.exe"
    Expand-Archive "$work/downloads/node.zip" "$work/downloads/node"
    $nodeDir = (Get-ChildItem "$work/downloads/node" -Directory | Select-Object -First 1).FullName
    Copy-Item "$nodeDir/node.exe" "$inputDir/tools/node.exe"
    Copy-Item "$nodeDir/LICENSE" "$inputDir/licenses/node-LICENSE.txt"
    Expand-Archive "$work/downloads/ffmpeg.zip" "$work/downloads/ffmpeg"
    $ffDir = (Get-ChildItem "$work/downloads/ffmpeg" -Directory | Select-Object -First 1).FullName
    Copy-Item "$ffDir/bin/*" "$inputDir/tools/"
    Copy-Item "$ffDir/LICENSE.txt" "$inputDir/licenses/ffmpeg-LICENSE.txt"
    if (Test-Path "$ffDir/doc") { Copy-Item "$ffDir/doc" "$inputDir/licenses/ffmpeg-doc" -Recurse }
    foreach ($name in @('LICENSE','THIRD_PARTY_LICENSES.txt')) {
        Invoke-WebRequest "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$($lock.ytDlp.version)/$name" -OutFile "$inputDir/licenses/yt-dlp-$name"
    }
    Copy-Item "$PSScriptRoot/tools-lock.json" "$inputDir/licenses/tools-lock.json"
    Copy-Item "$PSScriptRoot/THIRD-PARTY-NOTICES.md" "$inputDir/licenses/THIRD-PARTY-NOTICES.md"
    Run "$inputDir/tools/node.exe" @('--version')
    Run "$inputDir/tools/yt-dlp.exe" @('--version')
    Run "$inputDir/tools/ffmpeg.exe" @('-version')
    Run "$env:JAVA_HOME/bin/jlink.exe" @('--add-modules','java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.mscapi,jdk.charsets,jdk.localedata,jdk.httpserver,jdk.management,jdk.zipfs','--strip-debug','--no-header-files','--no-man-pages','--output',"$work/runtime")
    $uiJar = (Get-ChildItem "$inputDir/ajdm-ui-*.jar" | Select-Object -First 1).Name
    $cliJar = (Get-ChildItem "$inputDir/ajdm-cli-*.jar" | Select-Object -First 1).Name
    @("main-jar=$cliJar",'main-class=io.ajdm.cli.PackagedCore','win-console=false') | Set-Content "$work/core.properties"
    @("main-jar=$cliJar",'main-class=io.ajdm.cli.AjdmCli','win-console=true') | Set-Content "$work/cli.properties"
    Run "$env:JAVA_HOME/bin/jpackage.exe" @('--type','app-image','--dest',"$work/image",'--input',$inputDir,'--name','AJDM','--app-version','1.0.0','--vendor','Ariga','--description','AJDM v1 testing preview','--main-jar',$uiJar,'--main-class','io.ajdm.ui.PackagedLauncher','--runtime-image',"$work/runtime",'--java-options','--module-path=$APPDIR','--java-options','--add-modules=javafx.controls,javafx.fxml','--java-options','--enable-native-access=ALL-UNNAMED,javafx.graphics','--java-options','-Dajdm.version=1.0.0-preview.1','--add-launcher',"AJDM-core=$work/core.properties",'--add-launcher',"AJDM-cli=$work/cli.properties")
    Copy-Item "$image/*.exe" "$work/signing-input/"
} else {
    if (!(Test-Path "$image/AJDM.exe")) { throw 'Build the app image first.' }
    if (Test-Path "$work/signed-launchers") {
        foreach ($exe in Get-ChildItem "$work/signing-input/*.exe") {
            $signed = "$work/signed-launchers/$($exe.Name)"
            $signature = Get-AuthenticodeSignature $signed
            if ($signature.Status -ne 'Valid' -or !$signature.TimeStamperCertificate -or $signature.SignerCertificate.Subject -eq $signature.SignerCertificate.Issuer) { throw 'Launcher signature is not publicly trusted and timestamped.' }
            Copy-Item $signed "$image/$($exe.Name)" -Force
        }
    }
    if ($env:WIX -and (Test-Path "$env:WIX/bin")) { $env:PATH = "$env:WIX/bin;$env:PATH" }
    Run "$env:JAVA_HOME/bin/jpackage.exe" @('--type','exe','--app-image',$image,'--dest',"$work/installer",'--name','AJDM','--app-version','1.0.0','--vendor','Ariga','--description','AJDM v1 testing preview','--win-per-user-install','--win-dir-chooser','--win-menu','--win-menu-group','AJDM Preview','--win-shortcut','--install-dir','AJDM-v1-preview','--win-upgrade-uuid','a3b107eb-9bce-4ee6-a8e5-5144e5553597')
}
