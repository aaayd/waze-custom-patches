package local.wazemaps

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

internal fun Method.code() = implementation?.instructions?.toList().orEmpty()
internal fun Method.calls() = code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
internal fun Method.strings() = code().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
internal fun Method.parameters(vararg types: String) = parameterTypes.map(CharSequence::toString) == types.toList()
private fun <T> List<T>.unique(label: String): T = singleOrNull()
    ?: throw PatchException("Waze structure changed: $label matched $size candidates. No ambiguous hook will be applied.")

internal fun BytecodePatchContext.findMethod(label: String, test: (Method) -> Boolean): MutableMethod {
    val matches = mutableListOf<Method>()
    classDefForEach { type ->
        if (!type.type.startsWith("Llocal/wazemaps/")) type.methods.filterTo(matches, test)
    }
    val match = matches.unique(label)
    return mutableClassDefBy(match.definingClass).methods.single { it == match }
}

internal fun BytecodePatchContext.resourcePrepare(): MutableMethod = findMethod("resource preparation") {
    it.parameters() && it.returnType == "V" && "Resources extraction unnecessary" in it.strings() &&
        it.calls().any { ref -> ref.name == "getPackageInfo" && ref.definingClass == "Landroid/content/pm/PackageManager;" }
}

internal fun BytecodePatchContext.resourceHooks(): List<MutableMethod> {
    val prepare = resourcePrepare()
    val owner = mutableClassDefBy(prepare.definingClass)
    val extraction = owner.methods.filter { "assets/res/skins" in it.strings() && it.parameters("Z") && it.returnType == "V" }
        .toList().unique("skin extraction")
    if (prepare.calls().none { it == extraction }) throw PatchException("Skin preparation no longer invokes extraction")
    val reset = owner.methods.filter { method ->
        method.parameters() && method.returnType == "V" && method.calls().any { it == extraction } &&
            method.calls().any { it.definingClass == "Ljava/io/File;" && it.name == "delete" } && "version" in method.strings()
    }.toList().unique("resource reset")
    return listOf(prepare, reset)
}

internal fun BytecodePatchContext.settingsRenderer(): MutableMethod = findMethod("settings row renderer") { method ->
    method.returnType == "Landroid/view/View;" && method.parameterTypes.size == 1 && "page" in method.strings() &&
        AccessFlags.FINAL.isSet(method.accessFlags) && method.calls().any { ref ->
            ref.definingClass == method.definingClass && ref.returnType == "Landroid/view/View;" &&
                ref.parameterTypes == method.parameterTypes && classDefBy(method.definingClass).methods.any {
                    it == ref && AccessFlags.ABSTRACT.isSet(it.accessFlags)
                }
        }
}

internal fun BytecodePatchContext.assetLoader(): MutableMethod = findMethod("skin asset stream") {
    it.parameters("Ljava/lang/String;") && it.returnType == "Ljava/io/InputStream;" &&
        "res/skins/default/" in it.strings() && it.calls().any { ref ->
            ref.definingClass == "Landroid/content/res/AssetManager;" && ref.name == "open"
        }
}

internal fun BytecodePatchContext.moodScreen(): MutableMethod = findMethod("mood screen") {
    it.name == "onCreate" && it.parameters("Landroid/os/Bundle;") && it.returnType == "V" &&
        it.code().any { instruction -> ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.name == "CONFIG_VALUE_MOODS_BETA_ENABLED" }
}

internal fun BytecodePatchContext.configGetter(fields: List<String>, boxed: String, primitive: String): MutableMethod {
    val values = classDefBy("Lcom/waze/config/ConfigValues;")
    val types = fields.map { name -> values.fields.filter { it.name == name }.toList().unique("config $name").type }.distinct()
    val type = types.unique("common config type")
    return mutableClassDefBy(type).methods.filter { method ->
        method.parameters() && method.returnType == boxed && method.implementation != null &&
            method.calls().any { it.returnType == primitive && it.parameterTypes.map(CharSequence::toString) == listOf(type) } &&
            method.calls().any { it.definingClass == boxed && it.name == "valueOf" }
    }.toList().unique("$boxed config getter").also {
        if (it.implementation!!.registerCount < 2) throw PatchException("Config getter has no scratch register")
    }
}

private fun BytecodePatchContext.configId(getter: Method): String {
    // Resolve the identifier getter through the actual native read, not a guessed int field.
    val primitive = if (getter.returnType == "Ljava/lang/Long;") "J" else "Z"
    val target = if (primitive == "J") "getConfigValueLongNTV" else "getConfigValueBoolNTV"
    val method = classDefBy("Lcom/waze/ConfigManager;").methods.filter { method ->
        method.parameters(getter.definingClass) && method.returnType == primitive &&
            method.calls().any { it.definingClass == "Lcom/waze/ConfigManager;" && it.name == target }
    }.toList().unique("native config reader")
    val nativeIndex = method.code().indexOfFirst {
        val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
        ref?.definingClass == "Lcom/waze/ConfigManager;" && ref.name == target
    }
    if (nativeIndex < 2) throw PatchException("Native config read changed: $target")
    val instructions = method.code()
    val idRegister = when (val call = instructions[nativeIndex]) {
        is FiveRegisterInstruction -> call.registerD
        is RegisterRangeInstruction -> call.startRegister + 1
        else -> throw PatchException("Native config read register layout changed")
    }
    val resultIndex = (0 until nativeIndex).lastOrNull {
        (instructions[it] as? OneRegisterInstruction)?.registerA == idRegister && instructions[it].opcode == Opcode.MOVE_RESULT
    } ?: throw PatchException("Native config identifier result missing")
    if (resultIndex != nativeIndex - 1) throw PatchException("Native config identifier data flow changed")
    val id = ((instructions[resultIndex - 1] as? ReferenceInstruction)?.reference as? MethodReference)
        ?: throw PatchException("Native config identifier getter missing")
    if (id.parameterTypes.isNotEmpty() || id.returnType != "I") throw PatchException("Unexpected config identifier signature")
    val hierarchy = generateSequence(classDefBy(getter.definingClass)) { type -> type.superclass?.let { classDefByOrNull(it) } }
    if (hierarchy.none { it.type == id.definingClass }) throw PatchException("Config identifier belongs to a different type")
    val declaration = hierarchy.flatMap { it.methods }.firstOrNull {
        it.name == id.name && it.parameterTypes.isEmpty() && it.returnType == "I"
    } ?: throw PatchException("Config identifier declaration missing")
    if (!AccessFlags.PUBLIC.isSet(declaration.accessFlags)) throw PatchException("Config identifier is no longer public")
    return id.name
}

/** Rebind only the selected extension's reflection literals, including nested callback classes.
 * The source literals are fixture defaults; no target APK is identified by these old names.
 */
internal fun BytecodePatchContext.bindExtension(extension: String, context: Boolean = false, rows: Boolean = false, config: Method? = null) {
    fun api(type: String, name: String, result: String, static: Boolean, vararg parameters: String) {
        val method = classDefBy(type).methods.filter { it.name == name && it.returnType == result && it.parameters(*parameters) }
            .toList().unique("runtime API $type->$name")
        if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags) != static)
            throw PatchException("Runtime API access changed: $type->$name")
    }
    val native = "Lcom/waze/NativeManager;"
    api(native, "Post", "Z", true, "Ljava/lang/Runnable;")
    if (config != null || extension.endsWith("/AndroidAutoSettings;")) {
        api(native, "isAppStarted", "Z", true)
        api(native, "getInstance", native, true)
    }
    if (config != null) api("Lcom/waze/ConfigManager;", "getInstance", "Lcom/waze/ConfigManager;", true)
    if (extension.endsWith("/AndroidAutoSettings;")) {
        api(native, "isLoggedInNTV", "Z", false)
        val account = "Lcom/waze/mywaze/MyWazeNativeManager;"
        api(account, "getInstance", account, true)
        api(account, "isGuestUser", "Z", false)
    }
    val bindings = linkedMapOf<String, String>()
    if (context) {
        val prepare = resourcePrepare()
        val resolver = prepare.calls().filter {
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Class;") && it.returnType == "Ljava/lang/Object;"
        }.distinct().unique("application resolver")
        val resolved = classDefBy(resolver.definingClass).methods.filter { it == resolver }.toList().unique("application resolver method")
        if (!AccessFlags.PUBLIC.isSet(resolved.accessFlags) || !AccessFlags.STATIC.isSet(resolved.accessFlags) ||
            prepare.code().none { ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type == "Landroid/app/Application;" })
            throw PatchException("Application resolver contract changed")
        bindings["k.z"] = resolver.definingClass.removePrefix("L").removeSuffix(";").replace('/', '.')
        bindings["j"] = resolver.name
    }
    if (rows) {
        // The custom view name is part of Waze's XML layout contract, unlike its methods.
        val row = classDefBy("Lcom/waze/settings/tree/views/WazeSettingsView;")
        val title = row.methods.filter { method -> method.parameters("Ljava/lang/String;") && method.returnType == "V" &&
            method.calls().size == 1 && method.calls().single().let { it.name == "setText" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;") }
        }.toList().unique("settings title setter")
        val subtitle = row.methods.filter { method -> method.parameters("Ljava/lang/String;") && method.returnType == "V" &&
            method.calls().any { it.name == "getHint" } && method.calls().any { it.name == "setText" } && method.calls().any { it.name == "setVisibility" }
        }.toList().unique("settings subtitle setter")
        val style = row.methods.filter { method -> method.parameters("I") && method.returnType == "V" &&
            "layout_inflater" in method.strings() && method.calls().any { it.definingClass == "Landroid/view/LayoutInflater;" && it.name == "inflate" }
        }.toList().unique("settings style setter")
        for (method in listOf(title, subtitle, style)) if (!AccessFlags.PUBLIC.isSet(method.accessFlags))
            throw PatchException("Settings binding is no longer public")
        bindings.putAll(mapOf("N" to title.name, "P" to subtitle.name, "B" to style.name))
    }
    if (config != null) bindings["e"] = configId(config)
    val classes = mutableListOf<String>()
    classDefForEach { if (it.type == extension || it.type.startsWith(extension.removeSuffix(";") + "$")) classes.add(it.type) }
    val seen = mutableSetOf<String>()
    for (type in classes) for (method in mutableClassDefBy(type).methods) {
        method.code().forEachIndexed { index, instruction ->
            val value = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
            if (value in bindings) {
                val register = (instruction as OneRegisterInstruction).registerA
                val replacement = bindings.getValue(value!!)
                if (!replacement.matches(Regex("[A-Za-z0-9_.$]+"))) throw PatchException("Invalid discovered binding")
                method.replaceInstruction(index, "const-string v$register, \"$replacement\"")
                seen.add(value)
            }
        }
    }
    if (seen != bindings.keys) throw PatchException("Extension binding sites missing: ${bindings.keys - seen}")
    println("Waze bindings $extension: $bindings")
}
