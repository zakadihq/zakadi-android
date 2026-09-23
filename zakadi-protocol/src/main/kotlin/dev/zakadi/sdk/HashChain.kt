package dev.zakadi.sdk

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The hash chain the `attest` message carries (spec 01 section 1.4): `H0 = SHA256(utf8(session_id)
 * || jti_bytes)`, then `Hn = SHA256(Hn-1 || header8 || payload)` for each media message of type 0,
 * 1 or 3 in send order.
 */
@InternalZakadiApi
class HashChain(sessionId: String, jti: ByteArray) {
    private val md = MessageDigest.getInstance("SHA-256")
    private var h =
        md.digest(sessionId.toByteArray(Charsets.UTF_8) + jti.also { require(it.size == 16) })

    /**
     * Chains one media message in send order and returns true; a probe (type 2) leaves the chain
     * unchanged and returns false. [payload] is read from its position to its limit and left as it
     * was.
     */
    fun append(header: ByteArray, payload: ByteBuffer): Boolean {
        require(header.size == Header.SIZE) { "header is ${header.size} bytes, not ${Header.SIZE}" }
        if (((header[0].toInt() ushr 4) and 0x03) == Header.TYPE_PROBE) return false
        md.update(h)
        md.update(header)
        md.update(payload.duplicate())
        h = md.digest()
        return true
    }

    /** The latest chain value as lowercase hex, the `chain` field of `attest`. */
    fun hex(): String = h.toHexString()
}

/**
 * The claims of the client token, read without verifying it (spec 01 section 1.4): the SDK reads
 * them before it drops its references to the token.
 */
@InternalZakadiApi
fun tokenClaims(token: String): JsonObject {
    val parts = token.split('.')
    require(parts.size == 3) { "the client token is not a JWT" }
    val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
    return Json.parseToJsonElement(payload).jsonObject
}

/** The 16 raw bytes of the `jti` claim, which the token carries as base64url without padding. */
@InternalZakadiApi
fun jtiBytes(claims: JsonObject): ByteArray =
    Base64.getUrlDecoder().decode(claims.getValue("jti").jsonPrimitive.content).also {
        check(it.size == 16) { "jti is ${it.size} bytes, not 16" }
    }
