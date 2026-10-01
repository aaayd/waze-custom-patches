"""Upload to a draft first; expose a release only after every asset is present."""
import json
import os
import subprocess
from pathlib import Path
from upstream import release_exists


def main():
    root = Path("dist/nightly")
    info = json.loads((root / "build-info.json").read_text())
    tag = os.environ["RELEASE_TAG"]
    if tag != info["tag"]:
        raise ValueError("Release tag does not match validated input")
    if release_exists(os.environ["GITHUB_REPOSITORY"], tag):
        print("Release already published; rebuilt artifacts are available in this workflow run.")
        return
    required = [f"waze-{info['version']}-original-arm64{info['extension']}",
                f"waze-{info['version']}-patched-arm64.apk",
                f"waze-theme-selector-{info['bundle_version']}.mpp",
                "SHA256SUMS.txt", "build-info.json", "patch-report.json"]
    for name in required:
        if not (root / name).is_file():
            raise ValueError(f"Missing release asset: {name}")
    notes = Path("build/release-notes.md")
    notes.write_text(f"""Waze {info['version']} ({info['version_code']}), ARM64.

Assets: untouched original {info['extension']} package, signed pre-patched APK,
and Morphe bundle {info['bundle_version']} with seven independent options:
selectable themes, selectable report icon packs, detailed report icon sizing,
local rank badge selector, driver moods, Android Auto setup, and adjustable police alert distance.

{info.get('changes', '')}

All seven patches applied; bundle structure, signing, version metadata and
16 KiB ZIP alignment were checked. Automated builds are not device-runtime tested.
Inspect build-info.json, patch-report.json and SHA256SUMS.txt for the exact inputs.

The original split package needs a split-package installer or Morphe.
The patched APK uses this repository's persistent signing key; an existing
installation signed by another key cannot be updated with it.

Original source: {info['variant_url']}
Source commit: {info['source_commit']}
""")
    # An interrupted upload leaves a draft. Reuse it on retry, never a published release.
    found = subprocess.run(["gh", "release", "view", tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if found.returncode:
        subprocess.run(["gh", "release", "create", tag, "--draft", "--target", info["source_commit"],
                        "--title", f"Waze {info['version']} - Morphe patches", "--notes-file", str(notes)], check=True)
    subprocess.run(["gh", "release", "upload", tag, "--clobber", *[str(root / x) for x in required]], check=True)
    subprocess.run(["gh", "release", "edit", tag, "--draft=false", "--notes-file", str(notes)], check=True)


if __name__ == "__main__":
    main()
