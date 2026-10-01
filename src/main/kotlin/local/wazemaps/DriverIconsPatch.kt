package local.wazemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private fun Instruction.isMoodConstant(register: Int, value: Int): Boolean =
    opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST) &&
        this is OneRegisterInstruction && registerA == register &&
        this is NarrowLiteralInstruction && narrowLiteral == value

private fun hasMoodReturn(instructions: List<Instruction>, value: Int): Boolean =
    instructions.size >= 2 && instructions[0].isMoodConstant(0, value) &&
        instructions[1].opcode == Opcode.RETURN &&
        (instructions[1] as? OneRegisterInstruction)?.registerA == 0

@Suppress("unused")
val driverIconsPatch = bytecodePatch(
    name = "Unlock driver moods",
    description = "Unlock the mood picker, including Robot, 8-bit, Dino and beta moods, and expose special/hidden moods present in the local catalogue. Does not grant editor permissions or download unavailable campaigns.",
    default = true
) {
    compatibleWith(Compatibility(
        packageName = "com.waze", name = "Waze", apkFileType = ApkFileType.XAPK,
        signatures = setOf("03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"),
        targets = listOf(AppTarget(TARGET_WAZE_VERSION))
    ))
    execute {
        val manager = mutableClassDefBy("Lcom/waze/MoodManager;")
        val canSet = manager.methods.singleOrNull {
            it.name == "canSetMood" && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Ljava/lang/String;")
        } ?: throw PatchException("Expected Waze mood eligibility method")
        val baby = manager.methods.singleOrNull { it.name == "isBaby" && it.returnType == "Z" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("Expected Waze baby-mood gate")
        val refresh = manager.methods.singleOrNull { it.name == "refreshMoodsList" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("Expected Waze mood catalogue loader")
        val refreshInstructions = refresh.implementation!!.instructions.toList()
        val catalogueCalls = refreshInstructions.mapIndexedNotNull { index, instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            if (reference?.definingClass == manager.type && reference.name in setOf("getDefaultMoodListNTV", "getCustomMoodListNTV")) {
                if (reference.parameterTypes.map(CharSequence::toString) != listOf("Z", "Z") || instruction !is FiveRegisterInstruction) {
                    throw PatchException("Unexpected mood catalogue invocation")
                }
                index to instruction.registerD
            } else null
        }
        if (catalogueCalls.size != 2) throw PatchException("Expected two mood catalogue filters")

        val activity = mutableClassDefBy("Lcom/waze/mywaze/moods/MoodsActivity;")
        val create = activity.methods.single { it.name == "onCreate" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;") }
        val instructions = create.implementation!!.instructions.toList()
        val betaField = instructions.indices.singleOrNull { index ->
            ((instructions[index] as? ReferenceInstruction)?.reference as? FieldReference)?.name == "CONFIG_VALUE_MOODS_BETA_ENABLED"
        } ?: throw PatchException("Expected exactly one beta-mood visibility gate")
        val betaValue = (betaField + 1 until minOf(betaField + 8, instructions.size)).singleOrNull { index ->
            val ref = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Ljava/lang/Boolean;" && ref.name == "booleanValue"
        } ?: throw PatchException("Expected beta-mood Boolean result")
        val result = instructions[betaValue + 1]
        val betaRegister = (result as? OneRegisterInstruction)?.registerA
            ?: throw PatchException("Beta-mood result has no register: ${result.opcode}. Select the original Waze APK.")
        val alreadyUnlocked = result.isMoodConstant(betaRegister, 1)
        if (result.opcode != Opcode.MOVE_RESULT && !alreadyUnlocked)
            throw PatchException("Expected beta-mood result or an already-unlocked constant; found ${result.opcode}. Select waze-5.24.5.0-original-arm64.apkm from Downloads.")

        // All fingerprints validated before changing code. Only cosmetic gates are changed.
        if (!hasMoodReturn(canSet.implementation!!.instructions.toList(), 1))
            canSet.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        if (!hasMoodReturn(baby.implementation!!.instructions.toList(), 0))
            baby.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        if (!alreadyUnlocked) create.replaceInstruction(betaValue + 1, "const/16 v$betaRegister, 0x1")
        // Native first parameter includes special/hidden entries; the second controls sorting.
        catalogueCalls.sortedByDescending { it.first }.forEach { (index, register) ->
            if (index == 0 || !refreshInstructions[index - 1].isMoodConstant(register, 1))
                refresh.addInstructions(index, "const/16 v$register, 0x1")
        }
    }
}
