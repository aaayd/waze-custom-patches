"""Portable, fail-closed seven-patch release builder. Requires JDK 21 + Android SDK 36."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path
from urllib.request import urlopen
from release_config import BUNDLE_SERIES, RELEASE_NOTES
from alignment import normalise_native_alignment

ROOT = Path(__file__).resolve().parents[1]
DESKTOP_HASH = "36e20d7a18f655fb5829ae50aadd61217e2208536c0741df5f7799300f758f56"
COMPANION_HASH = "e5d442454418efd4ff438b3ed3f6bfa5a3aee78f52616625cb25ce0ec8855b48"
WAZE_CERT = "03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"
OPTIONS = {"Selectable map themes", "Detailed report icons at normal sizes",
           "Rank badge selector", "Unlock driver moods", "Android Auto setup", "Selectable report icon packs", "Android Auto police alert distance"}


def sha(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def run(*args, capture=False):
    args = [str(x) for x in args]
    print("+ " + " ".join(args), flush=True)
    result = subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.STDOUT if capture else None)
    return result.stdout or ""


def inspect_apk(apk, aapt, signer):
    badging = run(aapt, "dump", "badging", apk, capture=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not match:
        raise ValueError("Could not inspect package identity")
    signature = run(signer, "verify", "--print-certs", apk, capture=True)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", signature)
    return {"package": match[1], "code": int(match[2]), "version": match[3],
            "certificates": [x.lower() for x in certificates],
            "split": bool(re.search(r"^package:.* split='[^']+'", badging, re.MULTILINE))}


def inspect_original(source, scratch, aapt, signer, metadata):
    apks = []
    if source.suffix == ".apk":
        apks = [source]
    else:
        with zipfile.ZipFile(source) as archive:
            for i, name in enumerate(archive.namelist()):
                if name.endswith(".apk"):
                    path = scratch / f"original-{i}.apk"
                    path.write_bytes(archive.read(name))
                    apks.append(path)
    if not apks:
        raise ValueError("Original archive contains no APKs")
    native_hash = None
    base_count = 0
    for apk in apks:
        identity = inspect_apk(apk, aapt, signer)
        if not identity["split"]:
            base_count += 1
        if (identity["package"] != "com.waze" or identity["code"] != metadata["version_code"]
                or (not identity["split"] and identity["version"] != metadata["version"])
                or identity["certificates"] != [WAZE_CERT]):
            raise ValueError(f"Original package identity/signature mismatch: {identity}")
        with zipfile.ZipFile(apk) as archive:
            if "lib/arm64-v8a/libwaze.so" in archive.namelist():
                native_hash = hashlib.sha256(archive.read("lib/arm64-v8a/libwaze.so")).hexdigest()
    if base_count != 1:
        raise ValueError("Expected exactly one base APK")
    if not native_hash:
        raise ValueError("Original package does not include the ARM64 renderer")
    return native_hash


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", default="build/upstream.json")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--alias", default="Morphe")
    args = parser.parse_args()
    os.chdir(ROOT)
    metadata = json.loads(Path(args.metadata).read_text())
    version = metadata["version"]
    if not re.fullmatch(r"\d+(\.\d+){3}", version) or not isinstance(metadata["version_code"], int):
        raise ValueError("Invalid upstream version metadata")
    source = (args.input or Path(metadata["input"])).resolve()
    if not args.keystore.is_file():
        raise ValueError("Persistent release keystore is required")
    for key in ("WAZE_STORE_PASSWORD", "WAZE_KEY_PASSWORD"):
        if key not in os.environ:
            raise ValueError(f"Missing {key}")
    work = ROOT / "build" / "nightly"
    # This fixed path is inside build/, never derived from external metadata.
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    output = ROOT / "dist" / "nightly"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    sdk_tools = sdk / "build-tools" / "36.0.0"
    android = sdk / "platforms" / "android-36" / "android.jar"
    windows = os.name == "nt"
    java_bin = Path(os.environ["JAVA_HOME"]) / "bin"
    java = java_bin / ("java.exe" if windows else "java")
    javac = java_bin / ("javac.exe" if windows else "javac")
    jar = java_bin / ("jar.exe" if windows else "jar")
    d8 = sdk_tools / ("d8.bat" if windows else "d8")
    aapt = sdk_tools / ("aapt2.exe" if windows else "aapt2")
    signer = sdk_tools / ("apksigner.bat" if windows else "apksigner")
    align = sdk_tools / ("zipalign.exe" if windows else "zipalign")
    native_hash = inspect_original(source, work, aapt, signer, metadata)
    metadata.update(original_sha256=sha(source), native_sha256=native_hash)
    (output / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    desktop = ROOT / "tools" / "morphe-desktop.jar"
    desktop.parent.mkdir(exist_ok=True)
    if not desktop.exists():
        with urlopen("https://github.com/MorpheApp/morphe-desktop/releases/download/v1.18.0/morphe-desktop-1.18.0-all.jar", timeout=120) as response:
            with desktop.open("wb") as target:
                shutil.copyfileobj(response, target)
    if sha(desktop) != DESKTOP_HASH:
        raise ValueError("Unexpected Morphe Desktop dependency")
    companion = ROOT / "companion" / "waze-aa-installer-1.0.0.apk"
    if sha(companion) != COMPANION_HASH:
        raise ValueError("Unexpected companion installer")
    extensions = {}
    for folder, dex_name in [("theme-extension", "theme-selector.dex"), ("badge-extension", "badge-selector.dex"), ("aa-extension", "aa-installer.dex"), ("icon-extension", "icon-pack.dex"), ("alert-extension", "alert-distance.dex")]:
        classes = work / folder / "classes"
        dex = work / folder / "dex"
        classes.mkdir(parents=True)
        dex.mkdir()
        sources = sorted((ROOT / folder / "src").rglob("*.java"))
        run(javac, "--release", "11", "-cp", android, "-d", classes, *sources)
        archive = work / (folder + ".jar")
        run(jar, "cf", archive, "-C", classes, ".")
        run(d8, "--release", "--min-api", "29", "--lib", android, "--output", dex, archive)
        extensions[dex_name] = dex / "classes.dex"
    # Run the production decorators against small JVM view fixtures in every order.
    settings_classes = work / "settings-tests"
    settings_classes.mkdir()
    settings_cp = os.pathsep.join([str(android)] + [str(work / (name + ".jar"))
        for name in ["theme-extension", "icon-extension", "aa-extension", "alert-extension"]])
    run(javac, "-cp", settings_cp, "-d", settings_classes, *sorted((ROOT / "ci/settings-fixtures").rglob("*.java")), *sorted((ROOT / "ci/alert-fixtures").rglob("*.java")))
    run(java, "-cp", os.pathsep.join([str(settings_classes), settings_cp]), "ValidateSettingsRows")
    run(java, "-cp", os.pathsep.join([str(settings_classes), settings_cp]), "local.wazemaps.alerts.ValidateAlertDistance")
    bundle_version = f"{BUNDLE_SERIES}.{metadata['version_code']}"
    gradle = [str(ROOT / "gradlew.bat")] if windows else ["bash", str(ROOT / "gradlew")]
    run(*gradle, "themesJar", "--no-daemon", "--console=plain", f"-PwazeVersion={version}", f"-PbundleVersion={bundle_version}")
    patch_jar = ROOT / "build" / "libs" / f"waze-theme-selector-{bundle_version}.jar"
    patch_dex = work / "patch-dex"
    patch_dex.mkdir()
    run(d8, "--release", "--min-api", "26", "--lib", android, "--classpath", desktop, "--output", patch_dex, patch_jar)
    bundle = output / f"waze-theme-selector-{bundle_version}.mpp"
    shutil.copyfile(patch_jar, bundle)
    with zipfile.ZipFile(bundle, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(patch_dex / "classes.dex", "classes.dex")
        for name, path in extensions.items():
            archive.write(path, "extensions/" + name)
        archive.write(companion, "installer/waze-aa-installer.apk")
    run(javac, "-cp", desktop, "-d", work, ROOT / "ci/java/ValidateBundleOptions.java")
    run(java, "-cp", os.pathsep.join([str(work), str(desktop)]), "ValidateBundleOptions", bundle)
    unsigned = work / "patched-unsigned.apk"
    report = output / "patch-report.json"
    run(java, "-Xmx4g", "-jar", desktop, "patch", "--unsigned", "--bytecode-mode", "STRIP_SAFE",
        "--striplibs", "arm64-v8a", "-p", bundle, "-o", unsigned, "-r", report, source)
    result = json.loads(report.read_text())
    if (result.get("failedPatches") or {x["name"] for x in result.get("appliedPatches", [])} != OPTIONS
            or not result.get("patchingSteps") or any(not x["success"] for x in result["patchingSteps"])):
        raise ValueError("All seven patches must succeed before publishing")
    run(javac, "-cp", desktop, "-d", work, ROOT / "ci/java/ValidatePatchSelection.java")
    validation_cp = os.pathsep.join([str(work), str(desktop)])
    run(java, "-cp", validation_cp, "ValidatePatchSelection", unsigned, "true", "true", "true", "true", metadata["version_code"])
    for name, theme, icons, auto, alerts, filename in [
            ("Selectable map themes", "true", "false", "false", "false", "themes-only"),
            ("Selectable report icon packs", "false", "true", "false", "false", "icons-only"),
            ("Android Auto setup", "false", "false", "true", "false", "aa-only"),
            ("Android Auto police alert distance", "false", "false", "false", "true", "alerts-only")]:
        subset = work / (filename + ".apk")
        run(java, "-Xmx4g", "-jar", desktop, "patch", "--unsigned", "--bytecode-mode", "STRIP_SAFE",
            "--striplibs", "arm64-v8a", "--exclusive", "-e", name, "-p", bundle, "-o", subset, source)
        run(java, "-cp", validation_cp, "ValidatePatchSelection", subset, theme, icons, auto, alerts, metadata["version_code"])
    aligned = work / "patched-aligned.apk"
    run(align, "-f", "-P", "16", "4", unsigned, aligned)
    # apksig prioritises existing local ZIP alignment hints over its page-size setting.
    print(f"Updated {normalise_native_alignment(aligned)} native alignment hints before signing", flush=True)
    patched = output / f"waze-{version}-patched-arm64.apk"
    run(javac, "-cp", desktop, "-d", work, ROOT / "ci/java/SignRelease.java")
    run(java, "-cp", os.pathsep.join([str(work), str(desktop)]), "SignRelease",
        args.keystore.resolve(), args.alias, aligned, patched)
    run(align, "-c", "-P", "16", "4", patched)
    identity = inspect_apk(patched, aapt, signer)
    if identity["package"] != "com.waze" or identity["version"] != version or identity["code"] <= metadata["version_code"]:
        raise ValueError("Patched package identity/version is incorrect")
    expected_cert = os.environ.get("WAZE_RELEASE_CERT_SHA256")
    if expected_cert and identity["certificates"] != [expected_cert.lower()]:
        raise ValueError("Release signing certificate changed")
    with zipfile.ZipFile(patched) as archive:
        embedded = archive.read("assets/morphe/installer/waze-aa-installer.apk")
        if hashlib.sha256(embedded).hexdigest() != COMPANION_HASH:
            raise ValueError("Embedded Android Auto installer mismatch")
    original = output / f"waze-{version}-original-arm64{source.suffix}"
    shutil.copyfile(source, original)
    metadata.update(bundle_version=bundle_version, patched=identity, changes=RELEASE_NOTES,
                    source_commit=run("git", "rev-parse", "HEAD", capture=True).strip(),
                    validation="Seven patches, all 128 option combinations, independent theme, icon, Android Auto and alert APKs, signatures, package metadata, 16 KiB ZIP alignment. No device runtime test in CI.")
    (output / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (output / "SHA256SUMS.txt").write_text("".join(f"{sha(p)}  {p.name}\n" for p in sorted(output.iterdir()) if p.is_file()))
    print(f"Release ready: {output}")


if __name__ == "__main__":
    main()
