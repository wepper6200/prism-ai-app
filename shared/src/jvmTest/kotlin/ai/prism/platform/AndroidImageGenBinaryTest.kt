package ai.prism.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidImageGenBinaryTest {

    private val modern = """
        processor	: 0
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp i8mm bf16
        processor	: 7
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp i8mm bf16
    """.trimIndent()

    private val mixed = """
        processor	: 0
        Features	: fp asimd asimddp
        processor	: 4
        Features	: fp asimd asimddp i8mm
    """.trimIndent()

    @Test
    fun i8mmIsUsedOnlyWhenEveryCoreHasIt() {
        assertTrue(AndroidImageGenBinary.hasI8mm(modern))
        assertFalse(AndroidImageGenBinary.hasI8mm(mixed))
        assertFalse(AndroidImageGenBinary.hasI8mm(""))
    }

    @Test
    fun fastBuildIsPickedWhenItIsInstalled() {
        val dir = Files.createTempDirectory("prism-libs").toFile()
        try {
            File(dir, "libprism_imagegen.so").writeText("base")
            assertEquals("libprism_imagegen.so", AndroidImageGenBinary.pick(dir) { modern }.name)
            File(dir, "libprism_imagegen_i8mm.so").writeText("fast")
            assertEquals("libprism_imagegen_i8mm.so", AndroidImageGenBinary.pick(dir) { modern }.name)
            assertEquals("libprism_imagegen.so", AndroidImageGenBinary.pick(dir) { mixed }.name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
