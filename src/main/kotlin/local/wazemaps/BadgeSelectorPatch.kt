package local.wazemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.reandroid.arsc.chunk.TableBlock

private const val BADGE_EXTENSION = "Llocal/wazemaps/badges/BadgeSelector;"

private val badgeResources = rawResourcePatch {
    dependsOn(refreshWazeSkins)
    execute {
        val table = get("resources.arsc").inputStream().use { TableBlock.load(it) }
        for ((type, names) in mapOf("drawable" to listOf("crown", "sword", "shield", "edit", "wings"),
            "id" to listOf("moodList", "headerView"))) {
            for (name in names) if (table.getResource("com.waze", type, name) == null)
                throw PatchException("Waze badge resource missing: $type/$name")
        }
    }
}

@Suppress("unused")
val badgeSelectorPatch = bytecodePatch(
    name = "Rank badge selector",
    description = "Add a persistent badge appearance selector to the Mood screen: crown, sword, shield, editor, wings, none or automatic. Local display only; account rank and what other users receive are unchanged.",
    default = true
) {
    compatibleWith(Compatibility(
        packageName = "com.waze", name = "Waze", apkFileType = ApkFileType.XAPK,
        signatures = setOf("03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"),
        targets = TESTED_WAZE_VERSIONS.map { AppTarget(it) }
    ))
    dependsOn(badgeResources)
    extendWith("extensions/badge-selector.dex")
    execute {
        val mood = mutableClassDefBy("Lcom/waze/MoodManager;")
        val badge = mood.methods.singleOrNull {
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
            it.returnType == "Landroid/graphics/drawable/Drawable;" &&
            it.strings().containsAll(listOf("shield", "edit", "crown", "sword", "wings", "_ui.png")) }
            ?: throw PatchException("Expected Waze badge renderer")
        val create = moodScreen()
        val returns = create.implementation!!.instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf { instruction.opcode == Opcode.RETURN_VOID }
        }
        if (returns.isEmpty() || badge.implementation!!.registerCount < 3) throw PatchException("Unexpected Waze badge method layout")
        val activityRegister = create.implementation!!.registerCount - 2
        if (create.code().any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == activityRegister })
            throw PatchException("Mood screen reuses its Activity register before the badge hook")
        // 2 + (-2) == 0 selects the untouched original renderer for Automatic.
        badge.addInstructions(0, """
            invoke-static/range {p1 .. p1}, $BADGE_EXTENSION->selection(Landroid/content/Context;)I
            move-result v0
            add-int/lit8 v0, v0, 0x2
            if-eqz v0, :account_badge
            add-int/lit8 v0, v0, -0x2
            move-object/from16 v1, p1
            invoke-static {v1, v0}, $BADGE_EXTENSION->drawable(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;
            move-result-object v0
            return-object v0
            :account_badge
            nop
        """)
        returns.reversed().forEach { create.addInstructions(it, "invoke-static/range {p0 .. p0}, $BADGE_EXTENSION->install(Landroid/app/Activity;)V") }
    }
}
