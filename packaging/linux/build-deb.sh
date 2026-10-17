#!/usr/bin/env bash
# Builds the AJDM v1 preview Debian package (x86_64): build/linux/installer/ajdm_*.deb
# Needs Java 25 in JAVA_HOME, fakeroot and dpkg-deb. Pinned tools are cached in ~/.cache/ajdm-packaging,
# verified by SHA-256 on every build. ffmpeg comes from the distribution's package (a declared dependency).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$root"
work="$root/build/linux"; input="$work/input"; cache="${XDG_CACHE_HOME:-$HOME/.cache}/ajdm-packaging"
lock="$root/packaging/linux/tools-lock.json"
: "${JAVA_HOME:?Set JAVA_HOME to a Java 25 JDK}"
[ -e "$work" ] && { echo "Use a fresh build/linux directory for packaging." >&2; exit 1; }
mkdir -p "$input/tools" "$input/licenses" "$cache"
field() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]][sys.argv[3]])' "$lock" "$1" "$2"; }
fetch() { # name url sha256
    local file="$cache/$1"
    if [ ! -f "$file" ] || [ "$(sha256sum "$file" | cut -d' ' -f1)" != "$3" ]; then curl -fL --retry 5 -C - -o "$file.part" "$2" && mv "$file.part" "$file"; fi
    [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$3" ] || { echo "Checksum mismatch: $1" >&2; exit 1; }
}

./gradlew --no-daemon --console=plain :ajdm-ui:installDist :ajdm-cli:installDist
for lib in ajdm-ui/build/install/ajdm-ui/lib ajdm-cli/build/install/ajdm/lib; do
    for jar in "$lib"/*.jar; do
        dest="$input/$(basename "$jar")"
        if [ -e "$dest" ] && ! cmp -s "$dest" "$jar"; then echo "Conflicting library $(basename "$jar")" >&2; exit 1; fi
        cp "$jar" "$dest"
    done
done

fetch yt-dlp_linux "$(field ytDlp url)" "$(field ytDlp sha256)"
fetch node.tar.xz "$(field node url)" "$(field node sha256)"
install -m 0755 "$cache/yt-dlp_linux" "$input/tools/yt-dlp"
mkdir -p "$work/node" && tar -xJf "$cache/node.tar.xz" -C "$work/node" --strip-components=1
install -m 0755 "$work/node/bin/node" "$input/tools/node"
cp "$work/node/LICENSE" "$input/licenses/node-LICENSE.txt"
ytver="$(field ytDlp version)"
for name in LICENSE THIRD_PARTY_LICENSES.txt; do
    curl -fsSL --retry 5 -o "$input/licenses/yt-dlp-$name" "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$ytver/$name"
done
cp "$lock" "$input/licenses/tools-lock.json"
cp "$root/packaging/windows/THIRD-PARTY-NOTICES.md" "$input/licenses/THIRD-PARTY-NOTICES.md"
python3 "$root/tools/build_extension.py" "$input/browser"
"$input/tools/node" --version
"$input/tools/yt-dlp" --version

"$JAVA_HOME/bin/jlink" --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.httpserver,jdk.management,jdk.zipfs \
    --strip-debug --no-header-files --no-man-pages --output "$work/runtime"
ui_jar="$(basename "$(ls "$input"/ajdm-ui-*.jar | head -1)")"; cli_jar="$(basename "$(ls "$input"/ajdm-cli-*.jar | head -1)")"
# Only the desktop app gets a menu entry; the core runs in the background and the CLI belongs in a terminal.
printf 'main-jar=%s\nmain-class=io.ajdm.cli.PackagedCore\nlinux-shortcut=false\n' "$cli_jar" > "$work/core.properties"
printf 'main-jar=%s\nmain-class=io.ajdm.cli.AjdmCli\nlinux-shortcut=false\n' "$cli_jar" > "$work/cli.properties"
printf 'main-jar=%s\nmain-class=io.ajdm.cli.NativeHost\nlinux-shortcut=false\n' "$cli_jar" > "$work/browser.properties"
common=(--name AJDM --app-version 1.0.0 --vendor Ariga --description "AJDM v1 testing preview")
"$JAVA_HOME/bin/jpackage" --type app-image --dest "$work/image" --input "$input" "${common[@]}" \
    --main-jar "$ui_jar" --main-class io.ajdm.ui.PackagedLauncher --runtime-image "$work/runtime" \
    --java-options '--module-path=$APPDIR' --java-options '--add-modules=javafx.controls,javafx.fxml' \
    --java-options '--enable-native-access=ALL-UNNAMED,javafx.graphics' --java-options '-Dajdm.version=1.0.0-preview.1' \
    --add-launcher "AJDM-core=$work/core.properties" --add-launcher "AJDM-cli=$work/cli.properties" --add-launcher "AJDM-browser=$work/browser.properties"
"$JAVA_HOME/bin/jpackage" --type deb --app-image "$work/image/AJDM" --dest "$work/installer" "${common[@]}" \
    --linux-package-name ajdm --linux-app-release preview1 --linux-deb-maintainer "Ariga <ajdm-preview@localhost>" \
    --linux-menu-group Network --linux-app-category net --linux-shortcut \
    --linux-package-deps "ffmpeg, libgtk-3-0t64 | libgtk-3-0"
ls -l "$work/installer"
