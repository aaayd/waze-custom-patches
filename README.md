# Waze Custom Patches

Four independently selectable patches for use with Morphe:

1. **Selectable map themes**: Original, Google Maps and OLED light/dark themes, icon-pack selection, and Android Auto setup offered after login.
2. **Detailed report icons at normal sizes**: specific report icons at normal sizes when zooming out (ARM64 renderer).
3. **Rank badge selector**: local badge appearance.
4. **Unlock driver moods**: local mood-selection gates.

## Add to Morphe

[Add Waze Custom Patches to Morphe](https://morphe.software/add-source?github=aaayd/waze-custom-patches&name=Waze%20Custom%20Patches)

Alternatively, open **Sources > + > Remote** in Morphe and paste:

```text
https://github.com/aaayd/waze-custom-patches
```

Morphe reads `patches-bundle.json` from the repository, downloads the `.mpp`
bundle and shows Waze with four independent patch options. Remote sources receive
bundle updates through Morphe's normal source updater. The metadata always points
to a complete, successfully patched release.

The repository must be public for this link to work: Morphe fetches source metadata
without GitHub authentication. Until then, download the `.mpp` from Releases while
signed into GitHub and add it through **Sources > + > Local**.

Source format and UI: [Morphe's patch-source guide](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patch-sources.md).

## Nightly releases

GitHub Actions checks APKMirror at **00:00 UTC daily** (01:00 UK summer time).
If that Waze version and versionCode already has a published release, it stops
before downloading the app or setting up the Android build toolchain.
Otherwise it downloads the newest uploaded release's ARM64 package, verifies
Waze's original signing certificate on every split, and attempts all four patches.

A successful release contains:

- The **untouched original** `.apkm` split package (or `.apk` if upstream supplies one).
- A signed **pre-patched ARM64 APK** with all four patches.
- An updated **`.mpp` Morphe bundle** with four separate options targeting that version.
- Patch results, build metadata and SHA-256 checksums.

Original split packages need a split-package installer or Morphe. The patched
APK can be installed directly; Android Auto setup uses the embedded companion
installer and Shizuku. Each nightly uses the same signing key. Builds signed by
Morphe on your phone can use a different key and cannot necessarily be updated by
the downloadable APK. Do not uninstall to switch keys unless you intend to lose
local app data.

A new version is an **attempted port**, not guaranteed compatibility. The native
icon-sizing patch checks the exact renderer hash and instruction bytes; bytecode
and theme patches also validate their targets. If Waze changes these, the job
fails, retains diagnostics for 14 days and publishes nothing. It retries on the
next night until fixed. It never silently ships a subset of the four patches.
CI validates patching, bundle choices, signatures, package metadata and alignment;
it does not perform a phone/emulator runtime test.

The manual **Run workflow** button retries immediately. `force` rebuilds an
already released version into workflow artifacts without replacing that release.
Interrupted uploads remain drafts until a later successful upload finishes.
After publishing, the workflow updates `patches-bundle.json` and `CHANGELOG.md`
together. Runs with no new Waze version also check that this source metadata is
current, without rebuilding the APK or bundle. An interrupted metadata update is
therefore repaired on the next run.

## Repository setup

The workflow requires these encrypted repository Actions secrets:

| Secret | Value |
| --- | --- |
| `WAZE_KEYSTORE_BASE64` | Base64 of the persistent PKCS12 signing keystore |
| `WAZE_STORE_PASSWORD` | Keystore password |
| `WAZE_KEY_PASSWORD` | Key password; alias is `Morphe` |

Set repository variable `WAZE_RELEASE_CERT_SHA256` to the release certificate hash.
Keep a secure backup of this key: changing it prevents seamless APK updates.
Keys, personal phone captures, browser profiles and downloaded Waze packages are
excluded from Git. The small companion APK is pinned and included alongside its
source in `companion/`.

Enable Actions on the default branch. A private repository may consume GitHub
Actions minutes/storage from your account plan. Scheduled runs can be delayed by
GitHub; source-site availability can also cause a failed check.

## Local build

Requirements: Python 3.11+, JDK 21, Android SDK platform 36 and build-tools 36.0.0.
Set `JAVA_HOME` and `ANDROID_HOME`, and export the two password environment variables.

```sh
python -m pip install -r ci/requirements.txt
python -m unittest discover -s ci/tests -v
python ci/upstream.py check
python ci/upstream.py download
python ci/build.py --keystore /path/to/waze-release.p12
```

Outputs go to `dist/nightly/`. The builder downloads a hash-pinned Morphe Desktop
1.18.0 dependency and uses the checked-in Gradle wrapper. `--input /path/file.apkm`
allows a local original package, but its actual version and certificate must match
`build/upstream.json`. Patch target/version metadata is generated at build time;
source files are not rewritten by the nightly job.

`src/main/resources/themes/native-report-zoom.properties` contains the currently
supported renderer profile. A changed native library needs a reviewed update to
this profile and appropriate renderer tests. Never disable its hash/byte guards
merely to get a green nightly build.

Existing Windows scripts and detailed notes are retained for development:
[themes](THEME-SELECTOR.md), [icon sizing](REPORT-ICON-SIZING.md),
[badges](BADGE-SELECTOR.md), [moods](DRIVER-ICONS.md).
Older scripts may require ignored local analysis fixtures and Android tools;
`ci/build.py` is the self-contained release path.

This is an independent custom-patch project. Waze, Google Maps and Morphe are
third-party products; their names are used to describe compatibility.
