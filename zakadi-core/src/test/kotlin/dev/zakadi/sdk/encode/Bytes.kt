package dev.zakadi.sdk.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Bytes from ints, for readable fixtures. */
fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** [nals] joined with 3-byte start codes, as some encoders write them. */
fun stream3(vararg nals: ByteArray): ByteArray =
    nals.fold(ByteArray(0)) { acc, nal -> acc + bytes(0, 0, 1) + nal }

/** [nals] joined with 4-byte start codes. */
fun stream4(vararg nals: ByteArray): ByteArray =
    nals.fold(ByteArray(0)) { acc, nal -> acc + bytes(0, 0, 0, 1) + nal }

/** Fails unless [au] starts with `00 00 00 01` and every start code in it has 4 bytes. */
fun assertFourByteStartCodes(au: ByteArray) {
    assertTrue(
        "starts with 00 00 00 01",
        au.size > 4 && au.copyOf(4).contentEquals(bytes(0, 0, 0, 1)),
    )
    for (i in 0 until au.size - 2) {
        if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
            assertTrue("a 3-byte start code at $i", i >= 1)
            assertEquals("the byte before the start code at $i", 0, au[i - 1].toInt())
        }
    }
}

/** A plausible SPS (Constrained Baseline, level 3.1), PPS, IDR slice and non-IDR slice. */
val SPS = bytes(0x67, 0x42, 0xE0, 0x1F, 0xDA, 0x01, 0xE0, 0x08)
val PPS = bytes(0x68, 0xCE, 0x3C, 0x80)
val IDR = bytes(0x65, 0x88, 0x84, 0x21, 0xA0)
val SLICE = bytes(0x41, 0x9A, 0x02, 0x10)
val AUD = bytes(0x09, 0xF0)
