@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk

import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail

/**
 * Runs one framing case, a JSON file of `vectors/framing` in zakadi-protocol (spec 01 section
 * 1.12). [bin] is the content of the case's `.bin` file, which every `expect` case ships.
 */
fun checkFramingVector(json: String, bin: ByteArray?) {
    val case = Json.parseToJsonElement(json).jsonObject
    val name = case.string("name")
    val message = case.string("hex").hexToByteArray()
    if (bin != null) assertArrayEquals("$name: .bin differs from hex", message, bin)
    val expect = case["expect"]?.jsonObject
    if (expect == null) {
        val code = case.string("error")
        try {
            decodeMessage(message)
        } catch (e: FramingException) {
            assertEquals("$name: error code", code, e.error.code)
            return
        }
        fail("$name: decoded without the error $code")
    }
    assertNotNull("$name: an expect case ships a .bin", bin)
    val header = Header.decode(message)
    val want = expect!!.getValue("header").jsonObject
    assertEquals("$name: ver", want.int("ver"), Header.VERSION)
    assertEquals(
        "$name: header",
        MediaHeader(
            type = want.int("type"),
            keyframe = want.boolean("keyframe"),
            paramSets = want.boolean("param_sets"),
            rungChanged = want.boolean("rung_changed"),
            rung = want.int("rung"),
            seq = want.int("seq"),
            ptsMs = want.long("pts_ms"),
        ),
        header,
    )
    val payload = message.copyOfRange(Header.SIZE, message.size)
    assertEquals("$name: payload", expect.string("payload_hex"), payload.toHexString())
    val reencoded =
        with(header) { Header.encode(type, keyframe, paramSets, rungChanged, rung, seq, ptsMs) }
    assertArrayEquals("$name: re-encoded message", message, reencoded + payload)
    when (header.type) {
        Header.TYPE_PROBE ->
            assertEquals(
                "$name: probe_send_time_us",
                expect.long("probe_send_time_us"),
                parseProbePayload(payload),
            )
        Header.TYPE_AUDIO_BATCH ->
            assertEquals(
                "$name: audio_batch",
                expect.getValue("audio_batch").jsonArray.map {
                    it.jsonObject.int("pts_delta_ms") to it.jsonObject.string("packet_hex")
                },
                parseAudioBatch(payload).map { it.ptsDeltaMs to it.packet.toHexString() },
            )
    }
}

/**
 * Runs one chain case, a JSON file of `vectors/chain` in zakadi-protocol (spec 01 section 1.4,
 * `attest`): the jti read unverified from the token, H0, the chain after every message and the
 * `attest` value.
 */
fun checkChainVector(json: String) {
    val case = Json.parseToJsonElement(json).jsonObject
    val name = case.string("name")
    val jti = Base64.getUrlDecoder().decode(case.string("jti"))
    assertEquals("$name: jti length", 16, jti.size)
    val tokenJti = jtiBytes(tokenClaims(case.string("token")))
    assertArrayEquals("$name: jti read from the token", jti, tokenJti)
    val chain = HashChain(case.string("session_id"), tokenJti)
    assertEquals("$name: h0", case.string("h0"), chain.hex())
    case.getValue("messages").jsonArray.forEachIndexed { i, element ->
        val m = element.jsonObject
        val message = m.string("hex").hexToByteArray()
        val chained =
            chain.append(
                message.copyOfRange(0, Header.SIZE),
                ByteBuffer.wrap(message, Header.SIZE, message.size - Header.SIZE),
            )
        assertEquals("$name message $i: chained", m.boolean("chained"), chained)
        assertEquals("$name message $i: chain_after", m.string("chain_after"), chain.hex())
    }
    assertEquals(
        "$name: attest.chain",
        case.getValue("attest").jsonObject.string("chain"),
        chain.hex(),
    )
}

/** Decodes the header and, for a probe or an audio batch, the payload. */
private fun decodeMessage(message: ByteArray) {
    val header = Header.decode(message)
    val payload = message.copyOfRange(Header.SIZE, message.size)
    when (header.type) {
        Header.TYPE_PROBE -> parseProbePayload(payload)
        Header.TYPE_AUDIO_BATCH -> parseAudioBatch(payload)
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean
