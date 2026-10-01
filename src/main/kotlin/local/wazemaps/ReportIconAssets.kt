package local.wazemaps

import app.morphe.patcher.patch.*

private object ReportIconAssets

/** Shared resources are applied once, whichever public patches are selected. */
internal val reportIconAssets = rawResourcePatch {
    dependsOn(refreshWazeSkins)
    execute {
        fun resource(path: String) = ReportIconAssets::class.java.getResourceAsStream(path)
            ?: throw PatchException("Missing report icon resource: $path")
        val paths = resource("/iconpacks/alias-paths.txt").bufferedReader().use { it.readLines() }
            .filter(String::isNotEmpty)
        if (paths.isEmpty() || paths.distinct().size != paths.size)
            throw PatchException("Invalid sized icon manifest")
        for (path in paths) {
            if (path.contains("..") || path.contains('/') || path.contains('\\') || !path.endsWith(".png"))
                throw PatchException("Invalid sized icon path")
            val image = resource("/iconpacks/original_aliases/$path").use { it.readBytes() }
            val target = get("assets/res/skins/default/$path")
            target.parentFile.mkdirs()
            target.writeBytes(image)
        }
    }
}
