package local.wazemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode

private const val BADGE_EXTENSION = "Llocal/wazemaps/badges/BadgeSelector;"

@Suppress("unused")
val badgeSelectorPatch = bytecodePatch(
    name = "Rank badge selector",
    description = "Add a persistent badge appearance selector to the Mood screen: crown, sword, shield, editor, wings, none or automatic. Local display only; account rank and what other users receive are unchanged.",
    default = true
) {
    compatibleWith(Compatibility(
        packageName = "com.waze", name = "Waze", apkFileType = ApkFileType.XAPK,
        signatures = setOf("03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"),
        targets = listOf(AppTarget(TARGET_WAZE_VERSION))
    ))
    extendWith("extensions/badge-selector.dex")
    execute {
        val mood = mutableClassDefBy("Lcom/waze/MoodManager;")
        val badge = mood.methods.singleOrNull { it.name == "getUpScaledAddonDrawable" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
            it.returnType == "Landroid/graphics/drawable/Drawable;" }
            ?: throw PatchException("Expected Waze badge renderer")
        val activity = mutableClassDefBy("Lcom/waze/mywaze/moods/MoodsActivity;")
        val create = activity.methods.singleOrNull { it.name == "onCreate" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;") }
            ?: throw PatchException("Expected Waze Mood screen")
        val returns = create.implementation!!.instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf { instruction.opcode == Opcode.RETURN_VOID }
        }
        if (returns.size != 1 || badge.implementation!!.registerCount < 3) throw PatchException("Unexpected Waze badge method layout")
        // 2 + (-2) == 0 selects the untouched original renderer for Automatic.
        badge.addInstructions(0, """
            invoke-static/range {p1 .. p1}, $BADGE_EXTENSION->selection(Landroid/content/Context;)I
            move-result v0
            add-int/lit8 v0, v0, 0x2
            if-eqz v0, :account_badge
            add-int/lit8 v0, v0, -0x2
            invoke-static {p1, v0}, $BADGE_EXTENSION->drawable(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;
            move-result-object v0
            return-object v0
            :account_badge
            nop
        """)
        create.addInstructions(returns.single(), "invoke-static/range {p0 .. p0}, $BADGE_EXTENSION->install(Landroid/app/Activity;)V")
    }
}
