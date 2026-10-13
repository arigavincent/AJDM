# AJDM v1 Windows testing preview

This is installer version **1.0.0-preview.1**, for Windows 10/11 x64. It bundles Java 25, JavaFX, Node.js, yt-dlp and FFmpeg. Java/Node/Python do not need to be installed separately. This is a testing milestone, not completion of every PRD requirement.

## Signing status

Read `build-status.json` beside the installer. A file named `*-unsigned.exe` is **not signed**. No test certificate is installed and no claim of a trusted signature is made. An enrolled identity is required before the signed workflow can run. Do not disable Defender or install an arbitrary root certificate to run this preview; organization policies may prohibit unsigned apps.

For a personal publisher, the recommended integration is [SSL.com IV Code Signing](https://www.ssl.com/products/software-integrity/code-signing/iv/) plus [eSigner for Code](https://www.ssl.com/products/software-integrity/signing-service/). The published certificate price checked on 2026-10-13 is $129/year; cloud signing requires an additional subscription (the displayed Tier 1 price is $15/month, subject to billing selection and final checkout). Complete the provider's identity verification and confirm it can validate your country/document before purchasing. Public trust does not guarantee immediate SmartScreen reputation.

Enroll in your own SSL.com account using your legal name. Submit your ID only through the provider's verification process. Once approved, configure `ESIGNER_USERNAME`, `ESIGNER_PASSWORD`, `ESIGNER_CREDENTIAL_ID` and `ESIGNER_TOTP_SECRET` as GitHub Actions secrets for the private AJDM repository. Do not commit or send these secrets in chat. Dispatch **Windows v1 preview** with `trusted_signing=true`. It signs the three native launchers, then the installer, and requires valid timestamped Authenticode signatures. No CA enrollment or signing purchase has been performed automatically.

## Install and test

1. Copy the EXE, `SHA256SUMS.txt` and `build-status.json` to the Windows machine. Compare `Get-FileHash .\AJDM-*.exe -Algorithm SHA256` with the supplied hash.
2. Run the EXE as your normal user. Select the installation folder and use the AJDM Start Menu shortcut.
3. Paste a direct file or public YouTube link, analyze its formats, and select a quality. Try fixed eight connections and adaptive maximum eight separately using the same file.
4. Pause a download, close AJDM, reopen it, and resume. Compare file size and SHA-256 for a direct file with a known checksum. For selected video+audio, confirm the final muxed file plays with audio.
5. Check smaller/larger windows, cancel/remove controls and a network interruption. Report the visible error code and the steps that led to it.

The preview's state and safe core log are in `%LOCALAPPDATA%\AJDM\v1-preview`. Downloads default to `%USERPROFILE%\Downloads\AJDM-v1-preview`. Closing the launcher-owned UI requests an authenticated core shutdown and waits for saved checkpoints. Reopening preserves paused jobs. Uninstalling removes application files; it does not delete your downloaded files or preview history.

The Windows control endpoint binds only to IPv4 loopback. Its port and fresh 256-bit capability are stored in a private Windows ACL file. It is not an HTTP/web API and has no remote bind option. Linux/macOS retain their Unix socket transport. Do not share `core.endpoint`, databases, cookies or signed source URLs in bug reports.

## Build

Use Java 25 and WiX on Windows. Run `packaging/windows/Build-Windows.ps1 -Stage Image`, then `-Stage Installer`. Tool downloads are pinned with SHA-256. Windows JavaFX dependencies have checked-in verification metadata. The GitHub workflow runs portable model/HTTP and authenticated control/resume checks, starts the bundled JavaFX window/core, installs the actual EXE and starts the installed application.

Remaining acceptance includes real Windows user testing, cross-user ACL adversarial testing, live platform/cookie combinations, physical 4G/5G and matched IDM comparisons. Existing speed results do not demonstrate superiority over IDM.
