# Task: JavaFX UI for live AJDM testing

User asks a UI to test live downloads, speed/ETA, media choices, playlist and paused connection changes. Latest steering: find GitHub/Claude desktop/frontend skills and use suitable ones to continue the project. Follow PRD JavaFX/OpenJFX25, one existing core daemon over private IPC; do not replace or cancel the user's current download.

- [x] Verify relevant GitHub/Claude frontend/desktop guidance, retain PRD JavaFX direction and finish pinned UI dependencies/module.
- [x] Add testable safe IPC presentation and progress-rate/ETA model.
- [x] Build branded JavaFX live download list/details and file/media/playlist forms with controls.
- [x] Verify real IPC integration, GUI preview/interactions and affected regression; package independent launcher.
- [x] Document launch/test instructions, evidence and PRD limits.

Design: focused desktop transfer console, deep navy sidebar, warm off-white workspace, restrained brand accent, aligned numeric typography, dense clear rows and visible progress; live data with no invented throughput. User can change a paused job's workers to1..32.
Constraints: every payload byte remains AJDM engine-owned; UI only queries/commands the daemon, no second SQLite owner. Nonblocking FX thread, bounded polling/actions, catalog-owned errors and no source/header disclosure. Closing UI leaves downloads running. Native UI beyond current supported engine functions is acceptance-pending; do not present scheduler/proxy/tray placeholders as implemented.

Current: nine new display-independent tests pass, actual UI add/progress/pause/resume8/byte-verification plus media/playlist/About graphical check passed. Visual review corrected raw format kind/HDR text and selection size clipping; final source refinements and full regression check in progress. Existing engine/core/IPC source unchanged; legacy CLI distribution is untouched.

UI preview complete. Final UI check/packaging passed; nine unit/gateway tests and extended live GUI verified. Full regression issue is retained explicitly:447 cases with one unchanged100-item rate-spacing gate failure; isolated203.354-second rerun passed. No clean full-suite acceptance claimed. Independent installed UI opened against old, currently absent socket; no user core/state modifications. Source/package/XML/screenshots saved in artifacts/desktop-ui-preview.
