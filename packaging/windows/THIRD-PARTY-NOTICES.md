# AJDM Windows preview dependencies

The installer bundles Eclipse Temurin OpenJDK 25 (GPLv2 with Classpath Exception; runtime legal directory), OpenJFX 25.0.4 (GPLv2 with Classpath Exception; https://github.com/openjdk/jfx/tree/jfx25.0.4), Node.js 24.21.0 (license copied beside this notice), yt-dlp 2026.08.19 (upstream LICENSE and THIRD_PARTY_LICENSES copied here), and the BtbN FFmpeg 8.1 LGPL shared build (upstream license and documentation copied here). The FFmpeg DLLs are replaceable in app/tools; AJDM invokes ffmpeg as a separate local mux process.

Exact tool URLs, SHA-256 hashes and FFmpeg source/build references are in tools-lock.json. FFmpeg sources: https://github.com/FFmpeg/FFmpeg/tree/46d899d7a0 ; build recipes and dependencies: https://github.com/BtbN/FFmpeg-Builds/tree/autobuild-2026-10-12-13-12 . The upstream downloadable build is preserved by its checksum, and corresponding license documentation is bundled. Refer to those projects for source and build instructions.

Java libraries: SQLite JDBC (Apache-2.0/BSD, native SQLite public domain), Gson and Error Prone annotations (Apache-2.0), picocli (Apache-2.0). Their upstream license notices remain inside the dependency JARs. No third-party ownership is claimed by AJDM. The test preview is not a public software release.
