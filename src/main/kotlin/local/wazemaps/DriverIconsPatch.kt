package local.wazemaps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
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

internal fun babyMoodGate(eligibility: Method, methods: Iterable<Method>): MethodReference {
    val calls = eligibility.calls().filter {
        it.definingClass == eligibility.definingClass && it.returnType == "Z" && it.parameterTypes.isEmpty()
    }.distinct()
    if (calls.isNotEmpty()) return calls.singleOrNull()
        ?: throw PatchException("Expected one baby-mood eligibility call")

    // Newer optimizers inline the baby getter into eligibility. Require the
    // observed baby-only branch and an exact accessor of the same instance field.
    val original = eligibility.code()
    val code = if (hasMoodReturn(original, 1)) original.drop(2) else original
    fun invalid(): Nothing = throw PatchException("Expected verified inlined baby-mood eligibility gate")
    if (AccessFlags.STATIC.isSet(eligibility.accessFlags) || code.size < 11) invalid()
    val load = code[0] as? TwoRegisterInstruction ?: invalid()
    val field = (code[0] as? ReferenceInstruction)?.reference as? FieldReference ?: invalid()
    val branch = code[1] as? OffsetInstruction ?: invalid()
    if (code[0].opcode != Opcode.IGET_BOOLEAN || field.type != "Z" || field.definingClass != eligibility.definingClass ||
        load.registerB != eligibility.implementation!!.registerCount - 3 || code[1].opcode != Opcode.IF_EQZ ||
        (code[1] as? OneRegisterInstruction)?.registerA != load.registerA) invalid()
    val body = code.subList(2, 10)
    if (body.map { it.opcode } != listOf(Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CONST,
            Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.GOTO) ||
        branch.codeOffset != code.subList(1, 10).sumOf { it.codeUnits }) invalid()
    val references = body.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
    if (references.map { it.toString() } != listOf(
            "Landroid/content/Context;->getResources()Landroid/content/res/Resources;",
            "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;",
            "Ljava/lang/String;->equals(Ljava/lang/Object;)Z")) invalid()
    val resources = code[2] as FiveRegisterInstruction
    val text = code[5] as FiveRegisterInstruction
    val equals = code[7] as FiveRegisterInstruction
    val count = eligibility.implementation!!.registerCount
    if (resources.registerCount != 1 || resources.registerC != count - 2 ||
        text.registerCount != 2 || text.registerC != (code[3] as OneRegisterInstruction).registerA ||
        text.registerD != (code[4] as OneRegisterInstruction).registerA || equals.registerCount != 2 ||
        equals.registerC != count - 1 || equals.registerD != (code[6] as OneRegisterInstruction).registerA) invalid()
    val returnOffset = code.take(9).sumOf { it.codeUnits } + (code[9] as OffsetInstruction).codeOffset
    var offset = 0
    val returned = code.firstOrNull { instruction -> (offset == returnOffset).also { offset += instruction.codeUnits } }
    if (returned?.opcode != Opcode.RETURN || (returned as? OneRegisterInstruction)?.registerA !=
        (code[8] as OneRegisterInstruction).registerA) invalid()
    return methods.filter { method ->
        if (method.definingClass != eligibility.definingClass || method.returnType != "Z" ||
            method.parameterTypes.isNotEmpty() || AccessFlags.STATIC.isSet(method.accessFlags)) return@filter false
        val instructions = method.code().let { if (hasMoodReturn(it, 0)) it.drop(2) else it }
        val read = instructions.firstOrNull() as? TwoRegisterInstruction
        instructions.size == 2 && instructions[0].opcode == Opcode.IGET_BOOLEAN &&
            (instructions[0] as? ReferenceInstruction)?.reference == field &&
            read != null && read.registerB == method.implementation!!.registerCount - 1 && instructions[1].opcode == Opcode.RETURN &&
            (instructions[1] as? OneRegisterInstruction)?.registerA == read.registerA
    }.singleOrNull() ?: throw PatchException("Expected one accessor of the inlined baby-mood field")
}

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
        val canSet = findMethod("mood eligibility") {
            it.returnType == "Z" && it.parameters("Landroid/content/Context;", "Ljava/lang/String;") &&
                it.strings().containsAll(listOf("wazer_dino", "wazer_8bit", "wazer_robot"))
        }
        val manager = mutableClassDefBy(canSet.definingClass)
        val babyRef = babyMoodGate(canSet, manager.methods)
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
