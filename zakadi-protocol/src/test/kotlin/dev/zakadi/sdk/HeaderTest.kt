@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HeaderTest {
    @Test
    fun seqWrapsAsUint16() {
        val header = Header.encode(Header.TYPE_AUDIO, false, false, false, 2, 65536, 0)
        assertEquals(0, Header.decode(header).seq)
    }

    @Test
    fun encodeRejectsFieldsThatWouldSpillIntoOtherBits() {
        assertThrows(IllegalArgumentException::class.java) {
            Header.encode(4, false, false, false, 0, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Header.encode(Header.TYPE_VIDEO, false, false, false, 16, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Header.encode(Header.TYPE_VIDEO, false, false, false, 0, 0, 0x1_0000_0000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Header.encode(Header.TYPE_VIDEO, false, false, false, 0, 0, -1)
        }
    }
}
