package local.wazemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.Properties
import java.security.MessageDigest

private const val THEME_EXTENSION = "Llocal/wazemaps/themes/ThemeSelector;"
private object SelectableThemeResources

private fun themeProperties(name: String): Map<String, String> {
    val properties = Properties()
    val input = SelectableThemeResources::class.java.getResourceAsStream("/themes/$name.properties")
        ?: throw PatchException("Missing theme: $name")
    input.use(properties::load)
    return properties.stringPropertyNames().associateWith(properties::getProperty)
}

private val selectableThemeAssets = rawResourcePatch {
    dependsOn(reportIconAssets)
    execute {
        val companion = SelectableThemeResources::class.java.getResourceAsStream("/installer/waze-aa-installer.apk")
            ?.use { it.readBytes() } ?: throw PatchException("Missing bundled Android Auto installer")
        val hash = MessageDigest.getInstance("SHA-256").digest(companion).joinToString("") { "%02x".format(it) }
        if (hash != "e5d442454418efd4ff438b3ed3f6bfa5a3aee78f52616625cb25ce0ec8855b48")
            throw PatchException("Bundled Android Auto installer checksum mismatch")
        get("assets/morphe/installer/waze-aa-installer.apk").apply { parentFile.mkdirs(); writeBytes(companion) }
        // reportIconAssets has already staged the refreshed binary manifest.
        get("assets").resolveSibling("AndroidManifest.xml").apply { writeBytes(companionManifest(readBytes())) }
        val paths = SelectableThemeResources::class.java.getResourceAsStream("/iconpacks/paths.txt")
            ?.bufferedReader()?.use { it.readText() } ?: throw PatchException("Missing icon pack manifest")
        val index = get("assets/morphe/iconpacks/paths.txt")
        index.parentFile.mkdirs()
        index.writeText(paths)
        for (path in paths.lineSequence().filter(String::isNotEmpty)) {
            if (path.contains("..") || path.startsWith('/') || path.contains('\\') || !path.endsWith(".png"))
                throw PatchException("Invalid icon asset path: $path")
            val bytes = SelectableThemeResources::class.java.getResourceAsStream("/iconpacks/google_maps/$path")
                ?.use { it.readBytes() } ?: throw PatchException("Missing Google Maps icon: $path")
            if (!get("assets/res/skins/default/$path").isFile) throw PatchException("Missing Waze icon: $path")
            val target = get("assets/morphe/iconpacks/google_maps/$path")
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
        // Store additional palettes separately. Original APK skins remain byte-for-byte intact.
        for (variant in listOf("", "experiment/")) {
            for (mode in listOf("day", "night")) {
                val source = get("assets/res/skins/default/${variant}skin_values.$mode.lua").readText()
                val choices = mapOf(
                    "google_v2" to recolorSkin(source, themeProperties(mode), themeProperties("$mode.colors")),
                    "oled" to recolorSkin(source, themeProperties("night"), themeProperties("oled.colors"))
                )
                for ((id, text) in choices) {
                    val file = get("assets/morphe/themes/$id/${variant}skin_values.$mode.lua")
                    file.parentFile.mkdirs()
                    file.writeText(text)
                }
                // Editor mode has separate complete schemas. Keep its road-class colours,
                // changing only the background, land areas and label contrast for OLED.
                val editor = get("assets/res/skins/default/${variant}skin_values.editor.$mode.lua").readText()
                val editorPalette = mapOf(
                    "map_background" to "000000", "map_missing" to "000000",
                    "cities" to "000000", "stations" to "000000", "parking_lots" to "000000",
                    "labels" to "C4CCD7", "labels_strong" to "C4CCD7", "labels_bgcolor" to "000000",
                    "label_cities" to "C4CCD7", "label_station" to "C4CCD7",
                    "label_vegetation" to "82B891", "label_water" to "8AB4F8",
                    "ad_labels_color" to "C4CCD7", "ad_labels_outline_color" to "000000",
                    "parks" to "102820", "rivers" to "06121D", "lakes" to "06121D", "sea" to "06121D"
                )
                val areaGroups = setOf("Cities", "Stations", "ParkingLots", "Parks", "Rivers", "Lakes", "Sea")
                val editorColors = themeProperties("oled.colors").filterKeys {
                    it in setOf("General.map_background", "General.missing", "General.labels_bgcolor") ||
                        it.substringBefore('.') in areaGroups
                }
                val editorFile = get("assets/morphe/themes/oled/${variant}skin_values.editor.$mode.lua")
                editorFile.parentFile.mkdirs()
                editorFile.writeText(recolorSkin(editor, editorPalette, editorColors))
            }
        }
    }
}

@Suppress("unused")
val themeSelectorPatch = bytecodePatch(
    name = "Selectable map themes",
    description = "Add Themes, Icon pack and Android Auto setup below Dark mode. Includes Waze AA Installer with a first-launch setup offer.",
    default = true
) {
    compatibleWith(Compatibility(
        packageName = "com.waze", name = "Waze", apkFileType = ApkFileType.XAPK,
        signatures = setOf("03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"),
        targets = listOf(AppTarget(TARGET_WAZE_VERSION))
    ))
    dependsOn(selectableThemeAssets)
    extendWith("extensions/theme-selector.dex")
    execute {
        val launch = mutableClassDefBy("Lcom/waze/MainActivity;").methods.single {
            it.name == "onCreate" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;") && it.returnType == "V"
        }
        // MainActivity reuses p0 as a String later in onCreate. At entry it is
        // guaranteed to be the Activity; never read p0 at a return instruction.
        // Remove our older hook if this input already contains it.
        launch.implementation!!.instructions.toList().forEachIndexed { index, instruction ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            if (ref?.definingClass == "Llocal/wazemaps/themes/CompanionInstaller;" &&
                ref.name == "attach" && ref.parameterTypes.map(CharSequence::toString) == listOf("Landroid/app/Activity;"))
                launch.replaceInstruction(index, "nop")
        }
        launch.addInstructions(0, "invoke-static/range {p0 .. p0}, Llocal/wazemaps/themes/CompanionInstaller;->attach(Landroid/app/Activity;)V")
        val assets = mutableClassDefBy("Lcom/waze/resources/ResourcesNativeManager;").methods.single {
            it.name == "loadAssetStream" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                it.returnType == "Ljava/io/InputStream;"
        }
        if (assets.implementation!!.registerCount < 3 || assets.implementation!!.registerCount > 15)
            throw PatchException("Unexpected icon asset loader register layout")
        assets.addInstructions(0, """
            invoke-static {p1}, Llocal/wazemaps/themes/IconPack;->open(Ljava/lang/String;)Ljava/io/InputStream;
            move-result-object v0
            if-eqz v0, :original
            return-object v0
            :original
            nop
        """)
        val prepare = mutableClassDefBy("Lcom/waze/resources/i;").methods.single {
            it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        val reset = mutableClassDefBy("Lcom/waze/resources/i;").methods.single {
            it.name == "b" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        for (method in listOf(prepare, reset)) {
            val returns = method.implementation!!.instructions.mapIndexedNotNull { i, instruction ->
                i.takeIf { instruction.opcode == Opcode.RETURN_VOID }
            }
            if (returns.isEmpty()) throw PatchException("Resource preparation exit missing")
            returns.reversed().forEach {
                method.addInstructions(it, "invoke-static {}, $THEME_EXTENSION->prepare()V")
            }
        }
        val render = mutableClassDefBy("Lcom/waze/settings/tree/f;").methods.single {
            it.name == "k" && it.returnType == "Landroid/view/View;" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Lcom/waze/settings/de;")
        }
        if (render.implementation!!.registerCount > 15) throw PatchException("Unexpected settings register layout")
        val exits = render.implementation!!.instructions.mapIndexedNotNull { i, instruction ->
            if (instruction.opcode == Opcode.RETURN_OBJECT) i to (instruction as OneRegisterInstruction).registerA else null
        }
        if (exits.isEmpty()) throw PatchException("Settings renderer exit missing")
        exits.reversed().forEach { (i, register) ->
            render.addInstructions(i, """
                invoke-static/range {v$register .. v$register}, $THEME_EXTENSION->decorate(Landroid/view/View;)Landroid/view/View;
                move-result-object v$register
            """)
        }
    }
}
