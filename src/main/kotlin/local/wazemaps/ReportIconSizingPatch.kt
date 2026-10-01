package local.wazemaps

import app.morphe.patcher.patch.*
import java.security.MessageDigest
import java.util.Properties

private object ReportIconSizingResources

@Suppress("unused")
val reportIconSizingPatch = rawResourcePatch(
    name = "Detailed report icons at normal sizes",
    description = "Keep specific hazard icons when zooming out, using Waze's original small and tiny sizes. ARM64 only. Use alone or together with Selectable report icon packs from this bundle.",
    default = true
) {
    compatibleWith(Compatibility(
        packageName = "com.waze", name = "Waze", apkFileType = ApkFileType.XAPK,
        signatures = setOf("03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"),
        targets = listOf(AppTarget(TARGET_WAZE_VERSION))
    ))
    dependsOn(reportIconAssets)
    execute {
        fun resource(path: String) = ReportIconSizingResources::class.java.getResourceAsStream(path)
            ?: throw PatchException("Missing report icon resource: $path")
        val native = get("lib/arm64-v8a/libwaze.so")
        if (!native.isFile) throw PatchException("Report icon sizing requires the ARM64 Waze build")
        val rules = Properties().apply { resource("/themes/native-report-zoom.properties").use(::load) }
        val bytes = native.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (digest != rules.getProperty("source.sha256"))
            throw PatchException("Unsupported ARM64 renderer for Waze $TARGET_WAZE_VERSION (SHA-256 $digest). The native icon-sizing profile must be ported before this version can be released.")
        fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        for (key in rules.stringPropertyNames().filter { it.startsWith("patch.") }) {
            val offset = key.substringAfter('.').toInt(16)
            val (beforeHex, afterHex) = rules.getProperty(key).split(':')
            val before = unhex(beforeHex)
            val after = unhex(afterHex)
            if (before.size != after.size || offset < 0 || offset > bytes.size - before.size ||
                !bytes.copyOfRange(offset, offset + before.size).contentEquals(before))
                throw PatchException("Unexpected report-renderer patch site")
            after.copyInto(bytes, offset)
        }
        native.writeBytes(bytes)
    }
}
