package moe.shizuku.manager.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ModulePathPolicyTest {

    @Test
    fun acceptsFilesInsideModuleRoot() {
        val root = File.createTempFile("modroot", "").also {
            it.delete()
            it.mkdirs()
        }
        try {
            val child = File(root, "webroot/index.html").apply {
                parentFile.mkdirs()
                writeText("ok")
            }
            assertTrue(ModulePathPolicy.isInside(root, child))
            assertTrue(ModulePathPolicy.isInside(root, child.canonicalPath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversalOutsideModuleRoot() {
        val parent = File.createTempFile("modparent", "").also {
            it.delete()
            it.mkdirs()
        }
        try {
            val root = File(parent, "module").apply { mkdirs() }
            val secret = File(parent, "secret.txt").apply { writeText("nope") }
            val escaped = File(root, "../secret.txt")
            assertFalse(ModulePathPolicy.isInside(root, escaped))
            assertNull(AdbModuleManager.findFirstExisting(root, "../secret.txt"))
            assertNotNull(secret.takeIf { it.exists() })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun copyWithLimitRejectsOversizedStreams() {
        val input = ByteArrayInputStream(ByteArray(32) { 1 })
        val output = ByteArrayOutputStream()
        val thrown = runCatching {
            AdbModuleManager.copyWithLimit(input, output, maxBytes = 16)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertEquals("Module ZIP is too large.", thrown?.message)
        assertTrue(output.size() <= 16)
    }

    @Test
    fun copyWithLimitCopiesWithinBudget() {
        val payload = ByteArray(8) { 7 }
        val output = ByteArrayOutputStream()
        val copied = AdbModuleManager.copyWithLimit(
            ByteArrayInputStream(payload),
            output,
            maxBytes = 16
        )
        assertEquals(8L, copied)
        assertTrue(payload.contentEquals(output.toByteArray()))
    }
}
