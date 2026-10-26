#!/usr/bin/env python3
"""Assemble the AJDM browser extension for Chromium-family browsers and Firefox.

  python3 tools/build_extension.py [OUT_DIR]    (default build/extension)

Writes OUT_DIR/chrome and OUT_DIR/firefox (load unpacked) and ajdm-extension-{chrome,firefox}.zip.
"""
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "browser" / "extension"
FILES = ["logic.js", "background.js", "content.js", "options.html", "options.js", "popup.html", "popup.js"]


def build(out: Path) -> None:
    for browser in ("chrome", "firefox"):
        target = out / browser
        if target.exists():
            shutil.rmtree(target)
        (target / "icons").mkdir(parents=True)
        for name in FILES:
            shutil.copy2(SOURCE / name, target / name)
        for icon in (SOURCE / "icons").glob("*.png"):
            shutil.copy2(icon, target / "icons" / icon.name)
        shutil.copy2(SOURCE / f"manifest.{browser}.json", target / "manifest.json")
        archive = out / f"ajdm-extension-{browser}.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
            for path in sorted(target.rglob("*")):
                if path.is_file():
                    bundle.write(path, path.relative_to(target).as_posix())
        print(f"{browser}: {target} and {archive.name}")


if __name__ == "__main__":
    build(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build" / "extension")
