# Waze Custom Patches

Google Maps and OLED themes, Google Maps report icons, normal-sized detailed
report markers, a local badge selector, unlocked driver moods and Android Auto setup.
Six separate patches in one Morphe source.

## Install with Morphe

1. Install [Morphe](https://morphe.software/) and [add this patch source](https://morphe.software/add-source?github=aaayd/waze-custom-patches&name=Waze%20Custom%20Patches).
2. Download the **original ARM64 `.apkm`** from the [latest release](https://github.com/aaayd/waze-custom-patches/releases/latest).
3. In Morphe, choose Waze and select that file from Downloads. Select the features you want from the six independent patches listed below.
4. Patch and install Waze, then open it and sign in.

Prefer a ready-made app? Install the **patched ARM64 `.apk`** from the same release.
It uses a different signing key from phone-built copies. For an existing Morphe
installation, update through Morphe using the same key.

### If the source will not download

In **Sources > + > Remote**, paste:

```text
https://raw.githubusercontent.com/aaayd/waze-custom-patches/refs/heads/main/patches-bundle.json
```

Or download the `.mpp` from the latest release and select it through
**Sources > + > Local**.

## Choose your patches

- **Selectable map themes:** Original, Google Maps and OLED map colours.
- **Selectable report icon packs:** switch between Waze and Google Maps icons.
- **Detailed report icons at normal sizes:** detailed markers at the expected size, including when zoomed out.
- **Rank badge selector:** change your local badge appearance.
- **Unlock driver moods:** expose the bundled moods.
- **Android Auto setup:** include the installer and setup prompt for the Android Auto fix.

Enable any combination. The ready-made APK includes all six.

## Themes and icons

Open **Waze > Settings > Map display**:

- **Themes:** choose Original, Google Maps or OLED separately for light and dark mode.
- **Icon pack:** Google Maps is the default when no pack has been selected. You can switch to Waze original. Existing choices are kept.
- Tap **Apply & restart** after changing a theme or icon pack.

Traffic, road closures, blocked lanes and stopped-car reports use red icons in
the Google Maps pack. Enable **Detailed report icons at normal sizes** to keep
specific report artwork when zooming out.

## Waze missing from Android Auto?

1. Include the **Android Auto setup** patch when patching Waze. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/) using wireless debugging. Phone-only setup requires Android 11 or later.
2. Open Waze and sign in. Accept **Android Auto setup**, or open it from **Settings > Map display > Android Auto setup**.
3. Install the included **Waze AA Installer** when prompted. Allow Waze to install apps if Android asks.
4. Open Waze AA Installer, grant it Shizuku access and tap **Repair Android Auto visibility**. Confirm the Waze update in Android's installer.
5. Open **Android Auto > Customise launcher** and enable Waze, then reconnect to the car.

This reinstalls your current Waze without uninstalling it or clearing its data.
For future patches, choose **Waze AA Installer** in Morphe's installer chooser.
Start Shizuku again after restarting your phone. If the setup row is missing,
repatch with **Android Auto setup** enabled. Themes are optional.

## Updates

The repository checks for new Waze releases every night at **00:00 UTC**.
Morphe's source updater supplies new patch bundles. If a new Waze version cannot
be patched successfully, the previous working release stays available.

[Build and release notes](BUILDING.md)
