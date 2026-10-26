# Developing and testing AJDM on Windows

This page sets up a fresh Windows 10/11 machine to build AJDM and run the **full** regression suite, the same one Linux CI runs. It is the developer counterpart of the [Windows testing preview](WINDOWS_PREVIEW.md), which covers the installer.

## 1. Install the tools

| Tool | Why | Install (PowerShell, winget) |
|---|---|---|
| Git | clone; long paths | `winget install Git.Git` |
| Java 25 JDK | build and tests | `winget install EclipseAdoptium.Temurin.25.JDK` |
| Python 3 with pip | `tools/` tests; stand-in yt-dlp/ffmpeg fixtures | `winget install Python.PythonInstallManager`, then `py install 3` |
| FFmpeg (full build) | real mux and stream-hash tests | `winget install Gyan.FFmpeg` |
| Node.js LTS | browser-extension tests | `winget install OpenJS.NodeJS.LTS` |

Then, once:

```powershell
git config --global core.longpaths true   # Gradle/JavaFX build paths exceed 260 characters
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-25.<version>-hotspot", "User")
```

Open a new terminal so `JAVA_HOME` and `PATH` are picked up, and check: `java -version` (25), `py -3 -m pip --version`, `ffmpeg -version`, `node --version`.

**Optional, recommended: Developer Mode** (Settings → System → For developers). Windows only lets ordinary users create symbolic links in Developer Mode. Without it, the few tests that prove AJDM refuses to write through a symlink are *skipped* (reported as skipped, never as passed). Run them at least once before a release. Elevated terminals and the GitHub Windows runners can create links without it.

## 2. Run the regression suite

From the repository root:

```powershell
.\gradlew.bat --no-daemon --no-parallel --no-configuration-cache --max-workers=1 --console=plain check backendCoverageGate :ajdm-cli:distZip :ajdm-ui:distZip
node --test browser/extension/test/logic.test.mjs browser/extension/test/background.test.mjs
py -3 -m unittest discover -s tools/tests -v
```

The Gradle run takes about 15 minutes. Results are in `*/build/reports/tests/test/index.html`; coverage is in `build/reports/jacoco/backendCoverage/`.

`AJDM_TEST_FFMPEG` can point the media tests at a specific `ffmpeg.exe`; otherwise the first `ffmpeg.exe` on `PATH` is used.

## 3. How the tests differ by platform

The suite asserts the same behaviour on every OS. Where the operating system genuinely differs, each test uses the mechanism production uses there:

| Area | Linux / macOS | Windows |
|---|---|---|
| Local control (IPC) | private Unix-domain socket, `rw-------` | IPv4 loopback with a 256-bit capability in an owner-only ACL descriptor. The Unix-socket file rules are covered by `IpcIntegrationTest` on POSIX and skipped on Windows; the Windows equivalents are in `LoopbackIntegrationTest`. Protocol behaviour (invalid frames, invalid commands, slow peers) runs on both, over the real transport and authenticated, so a rejection proves validation rather than a missing token. |
| Private files | POSIX permission bits | ACL: owned by the user and no access entry for anyone else |
| Graceful core stop in tests | SIGTERM runs the shutdown hook | the core's own `shutdown` command, as the desktop launcher does. `Process.destroy()` is a hard kill on Windows. |
| Stand-in yt-dlp/ffmpeg | `#!/usr/bin/python3` script | the same Python script behind pip's console-script launcher (`.exe`), which forwards the raw command line. A `.cmd` wrapper is not used because `cmd.exe` would reinterpret `&` in URLs. |
| Symbolic links | always available | need Developer Mode or elevation; otherwise skipped |

## 4. Machine-specific pitfalls

- **A `package.json` above the repository** (for example `C:\Users\<you>\package.json` with `"type": "commonjs"`) used to break the extension tests. `browser/extension/package.json` now pins ES modules for those sources; it is not packaged.
- **Git Bash / MSYS terminals** set `TERM=xterm`, which makes picocli add colour codes. The CLI tests pin `picocli.ansi=false`.
- **"Filename too long"** from Git: set `core.longpaths` as above.
- **Store-alias Python** (`python.exe` that opens the Microsoft Store): install real Python with the commands above. The test fixtures resolve the actual interpreter through `py -3`.

## 5. Moving to a new machine

Everything needed to rebuild is in Git: the Gradle wrapper (checksummed), dependency lockfiles and verification metadata, and pinned packaging tools in `packaging/windows/tools-lock.json`. Work is safe only once it is **pushed**. Local state that is deliberately *not* in Git includes `artifacts/`, downloads, databases, cookies and any signing credentials; keep signing credentials in GitHub Actions secrets only. On the new machine: install section 1, `git clone`, `git checkout <branch>`, run section 2.
