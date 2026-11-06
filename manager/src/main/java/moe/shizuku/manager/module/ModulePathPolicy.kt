package moe.shizuku.manager.module

import java.io.File

/** Canonical-path containment checks shared by module install and WebUI loading. */
internal object ModulePathPolicy {

    fun isInside(root: File, child: File): Boolean {
        val rootPath = root.canonicalFile.canonicalPath
        val childPath = child.canonicalFile.canonicalPath
        return childPath == rootPath || childPath.startsWith("$rootPath/")
    }

    fun isInside(root: File, path: String): Boolean {
        return runCatching { isInside(root, File(path)) }.getOrDefault(false)
    }
}
