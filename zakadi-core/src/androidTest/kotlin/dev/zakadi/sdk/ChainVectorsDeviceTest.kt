package dev.zakadi.sdk

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The hash-chain vectors on a device, down to minSdk 26 (spec 05 5.15). */
@RunWith(Parameterized::class)
class ChainVectorsDeviceTest(private val name: String) {
    @Test
    fun chainVector() {
        checkChainVector(checkNotNull(vectorFile("chain", "$name.json")).decodeToString())
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = vectorCases("chain")
    }
}
