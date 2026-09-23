package dev.zakadi.sdk

import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ChainVectorsTest(private val name: String) {
    @Test
    fun chainVector() {
        checkChainVector(File(vectorDir("chain"), "$name.json").readText())
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = vectorCases("chain")
    }
}
