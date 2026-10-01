package local.wazemaps

import app.morphe.patcher.patch.PatchException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Waze only refreshes extracted skins when Android's versionCode increases. */
fun refreshSkinVersion(source: ByteArray, versionCode: Int = 1030733, increment: Int = 1): ByteArray {
    val result = source.copyOf()
    val data = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
    fun u16(offset: Int) = data.getShort(offset).toInt() and 0xffff
    if (result.size < 8 || u16(0) != 3 || data.getInt(4) != result.size) {
        throw PatchException("Expected a compiled Android manifest")
    }
    var resourceIds = IntArray(0)
    var offset = u16(2)
    var changed = 0
    while (offset < result.size) {
        if (offset + 8 > result.size) throw PatchException("Truncated manifest chunk")
        val type = u16(offset)
        val header = u16(offset + 2)
        val size = data.getInt(offset + 4)
        if (size < header || header < 8 || size > result.size - offset) {
            throw PatchException("Invalid manifest chunk")
        }
        if (type == 0x0180) {
            resourceIds = IntArray((size - header) / 4) { data.getInt(offset + header + it * 4) }
        } else if (type == 0x0102) {
            val ext = offset + header
            val start = u16(ext + 8)
            val stride = u16(ext + 10)
            val count = u16(ext + 12)
            if (stride < 20 || ext + start + stride * count > offset + size) {
                throw PatchException("Invalid manifest attributes")
            }
            repeat(count) { index ->
                val attr = ext + start + index * stride
                val name = data.getInt(attr + 4)
                if (resourceIds.getOrNull(name) == 0x0101021b) {
                    val valueType = result[attr + 15].toInt() and 0xff
                    val currentVersion = data.getInt(attr + 16)
                    if (valueType !in setOf(0x10, 0x11))
                        throw PatchException("Waze versionCode is not an integer")
                    if (increment <= 0 || currentVersion < 0 || currentVersion > Int.MAX_VALUE - increment || versionCode <= 0)
                        throw PatchException("Waze versionCode cannot be increased")
                    // Accept the input build number; never lower it or leave resources cached.
                    data.putInt(attr + 16, maxOf(versionCode, currentVersion + increment))
                    changed++
                }
            }
        }
        offset += size
    }
    if (changed != 1) throw PatchException("Expected exactly one Waze versionCode")
    return result
}

/** Replace palette entries and explicit renderer colours; preserve the original schema. */
fun recolorSkin(source: String, palette: Map<String, String>, colors: Map<String, String>): String {
    val start = source.indexOf("local Palette = {")
    val end = source.indexOf("\n}", start)
    if (start < 0 || end < 0) throw PatchException("Waze Lua palette was not found")
    var block = source.substring(start, end)
    for ((key, value) in palette) {
        if (!value.matches(Regex("[0-9A-F]{6}([0-9A-F]{2})?"))) {
            throw PatchException("Invalid theme colour for $key")
        }
        val entry = Regex("(?m)^(\\h*${Regex.escape(key)}\\h*=\\h*)rgba?\\(0x[0-9a-fA-F]+\\)")
        if (entry.findAll(block).count() != 1) {
            throw PatchException("Expected exactly one Waze palette entry: $key")
        }
        val function = if (value.length == 8) "rgba" else "rgb"
        block = entry.replace(block) { "${it.groupValues[1]}$function(0x$value)" }
    }
    val overrides = buildString {
        append("\n-- Google Maps sampled colours v2: bypass Waze's palette colour transforms.\n")
        val colorSource = source.substring(source.indexOf("Colors = {"))
        for ((path, value) in colors.toSortedMap()) {
            if (!path.matches(Regex("[A-Za-z][A-Za-z0-9]*\\.[A-Za-z][A-Za-z0-9_]*")) ||
                !value.matches(Regex("[0-9A-F]{6}([0-9A-F]{2})?"))) {
                throw PatchException("Invalid renderer colour: $path=$value")
            }
            val (group, key) = path.split('.')
            val groupMatch = Regex("(?ms)^\\h*${Regex.escape(group)}\\h*=\\h*\\{(.*?)^\\h*\\},").find(colorSource)
                ?: throw PatchException("Missing Waze colour group: $group")
            if (!Regex("(?m)^\\h*${Regex.escape(key)}\\h*=").containsMatchIn(groupMatch.groupValues[1])) {
                throw PatchException("Missing Waze renderer colour: $path")
            }
            val function = if (value.length == 8) "rgba" else "rgb"
            append("Colors.$path = $function(0x$value)\n")
        }
    }
    return source.substring(0, start) + block + source.substring(end) + overrides
}
