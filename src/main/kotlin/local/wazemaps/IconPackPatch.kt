package local.wazemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private object IconPackResources

private val selectableIconAssets = rawResourcePatch {
    dependsOn(reportIconAssets)
    execute {
        val paths = IconPackResources::class.java.getResourceAsStream("/iconpacks/paths.txt")
            ?.bufferedReader()?.use { it.readText() } ?: throw PatchException("Missing icon pack manifest")
        val index = get("assets/morphe/iconpacks/paths.txt")
        index.parentFile.mkdirs()
        index.writeText(paths)
        for (path in paths.lineSequence().filter(String::isNotEmpty)) {
            if (path.contains("..") || path.startsWith('/') || path.contains('\\') || !path.endsWith(".png"))
                throw PatchException("Invalid icon asset path: $path")
            val bytes = IconPackResources::class.java.getResourceAsStream("/iconpacks/google_maps/$path")
                ?.use { it.readBytes() } ?: throw PatchException("Missing Google Maps icon: $path")
            if (!get("assets/res/skins/default/$path").isFile) throw PatchException("Missing Waze icon: $path")
            val target = get("assets/morphe/iconpacks/google_maps/$path")
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
    }
}

@Suppress("unused")
val iconPackPatch = bytecodePatch(
    name = "Selectable report icon packs",
    description = "Add an Icon pack selector for original or Google Maps report icons. Defaults to Google Maps unless a choice is saved. Works independently of map themes and icon sizing.",
    default = true
) {
    compatibleWith(Compatibility(
        packageName = "com.waze", name = "Waze", apkFileType = ApkFileType.XAPK,
        signatures = setOf("03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"),
        targets = TESTED_WAZE_VERSIONS.map { AppTarget(it) }
    ))
    dependsOn(selectableIconAssets)
    extendWith("extensions/icon-pack.dex")
    execute {
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
                method.addInstructions(it, "invoke-static {}, Llocal/wazemaps/themes/IconPack;->prepare()V")
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
                invoke-static/range {v$register .. v$register}, Llocal/wazemaps/themes/IconPack;->decorate(Landroid/view/View;)Landroid/view/View;
                move-result-object v$register
            """)
        }
    }
}
