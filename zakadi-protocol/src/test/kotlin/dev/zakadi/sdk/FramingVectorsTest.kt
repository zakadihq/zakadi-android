package dev.zakadi.sdk

import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FramingVectorsTest(private val name: String) {
    @Test
    fun framingVector() {
        val dir = vectorDir("framing")
        val bin = File(dir, "$name.bin").takeIf { it.isFile }?.readBytes()
        checkFramingVector(File(dir, "$name.json").readText(), bin)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = vectorCases("framing")
    }
}
