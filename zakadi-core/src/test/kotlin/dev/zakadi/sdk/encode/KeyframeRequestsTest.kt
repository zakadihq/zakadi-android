@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeRequestsTest {
    private val ms = 1_000_000L

    @Test
    fun aRequestWithoutIdrIsRepeatedOnceAfter500Ms() {
        val requests = KeyframeRequests()
        requests.request(0)
        assertFalse(requests.repeatDue(499 * ms))
        assertTrue(requests.repeatDue(500 * ms))
        assertFalse(requests.repeatDue(900 * ms))
        assertFalse(requests.repeatDue(5_000 * ms))
        assertTrue(requests.pending)
    }

    @Test
    fun anIdrAnswersTheRequestWithItsDelayAndFrames() {
        val requests = KeyframeRequests()
        requests.request(10 * ms)
        repeat(3) { requests.onFrameSubmitted() }
        val answer = requests.onIdr(130 * ms)
        assertEquals(120L, answer?.msToIdr)
        assertEquals(3, answer?.framesToIdr)
        assertEquals(false, answer?.repeated)
        assertFalse(requests.pending)
        assertFalse(requests.repeatDue(600 * ms))
    }

    @Test
    fun anAnswerAfterTheRepeatSaysSo() {
        val requests = KeyframeRequests()
        requests.request(0)
        assertTrue(requests.repeatDue(500 * ms))
        val answer = requests.onIdr(700 * ms)
        assertEquals(700L, answer?.msToIdr)
        assertEquals(true, answer?.repeated)
    }

    @Test
    fun anIdrNobodyAskedForAnswersNothing() {
        val requests = KeyframeRequests()
        requests.onFrameSubmitted()
        assertNull(requests.onIdr(0))
    }

    @Test
    fun aNewRequestReplacesThePendingOne() {
        val requests = KeyframeRequests()
        requests.request(0)
        requests.request(300 * ms)
        assertFalse(requests.repeatDue(600 * ms))
        assertTrue(requests.repeatDue(800 * ms))
        assertEquals(600L, requests.onIdr(900 * ms)?.msToIdr)
    }
}
