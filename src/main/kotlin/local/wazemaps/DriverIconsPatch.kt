package local.wazemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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
        targets = TESTED_WAZE_VERSIONS.map { AppTarget(it) }
    ))
    dependsOn(refreshWazeSkins)
    execute {
        val manager = mutableClassDefBy("Lcom/waze/MoodManager;")
        val canSet = manager.methods.singleOrNull {
            it.returnType == "Z" && it.parameters("Landroid/content/Context;", "Ljava/lang/String;") &&
                it.strings().containsAll(listOf("wazer_dino", "wazer_8bit", "wazer_robot"))
        } ?: throw PatchException("Expected Waze mood eligibility method")
        val babyRef = canSet.calls().singleOrNull { it.definingClass == manager.type && it.returnType == "Z" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("Expected one baby-mood eligibility call")
        val baby = manager.methods.singleOrNull { it == babyRef }
            ?: throw PatchException("Expected Waze baby-mood gate")
        val refresh = manager.methods.singleOrNull { it.parameters() &&
            it.calls().map { ref -> ref.name }.containsAll(listOf("getDefaultMoodListNTV", "getCustomMoodListNTV")) }
            ?: throw PatchException("Expected Waze mood catalogue loader")
        val refreshInstructions = refresh.implementation!!.instructions.toList()
        val catalogueCalls = refreshInstructions.mapIndexedNotNull { index, instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            if (reference?.definingClass == manager.type && reference.name in setOf("getDefaultMoodListNTV", "getCustomMoodListNTV")) {
                if (reference.parameterTypes.map(CharSequence::toString) != listOf("Z", "Z")) {
                    throw PatchException("Unexpected mood catalogue invocation")
                }
                val register = when (instruction) {
                    is FiveRegisterInstruction -> instruction.registerD
                    is RegisterRangeInstruction -> instruction.startRegister + 1
                    else -> throw PatchException("Unknown mood invocation encoding")
                }
                if (register > 255) throw PatchException("Mood filter register exceeds const encoding")
                index to register
            } else null
        }
        if (catalogueCalls.size != 2) throw PatchException("Expected two mood catalogue filters")

        moodScreen()
        val create = moodGate()
        val (betaResult, betaRegister) = booleanConfigResult(create, "CONFIG_VALUE_MOODS_BETA_ENABLED")

        // All fingerprints validated before changing code. Only cosmetic gates are changed.
        if (!hasMoodReturn(canSet.implementation!!.instructions.toList(), 1))
            canSet.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        if (!hasMoodReturn(baby.implementation!!.instructions.toList(), 0))
            baby.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        create.replaceInstruction(betaResult, "const/16 v$betaRegister, 0x1")
        // Native first parameter includes special/hidden entries; the second controls sorting.
        catalogueCalls.sortedByDescending { it.first }.forEach { (index, register) ->
            if (index == 0 || !refreshInstructions[index - 1].isMoodConstant(register, 1))
                refresh.addInstructions(index, "const/16 v$register, 0x1")
        }
    }
}
