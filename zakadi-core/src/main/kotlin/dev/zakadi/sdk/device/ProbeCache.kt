@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.device

import android.content.Context
import dev.zakadi.sdk.InternalZakadiApi
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The capability probe's cache (spec 07 section 7.26): one report, keyed by the build and the probe
 * version, in `noBackupFilesDir` so that it neither leaves the device nor survives the install.
 */
internal class ProbeCache(context: Context) {
    private val file = File(context.noBackupFilesDir, "zakadi/capability-probe.json")

    /** The report cached under [key], or null. */
    fun read(key: String): ProbeReport? =
        try {
            if (!file.isFile) null
            else {
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                if (root.string("key") != key) null else decode(root.getValue("report").jsonObject)
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NoSuchElementException) {
            null
        }

    /** Caches [report] under [key]; a failure to write leaves no cache. */
    fun write(key: String, report: ProbeReport) {
        try {
            file.parentFile?.mkdirs()
            val root = buildJsonObject {
                put("key", key)
                put("report", encode(report))
            }
            file.writeText(root.toString())
        } catch (_: IOException) {
            file.delete()
        }
    }

    companion object {
        fun key(fingerprint: String, sdkInt: Int, probeVersion: Int): String =
            "$fingerprint|$sdkInt|$probeVersion"

        fun encode(report: ProbeReport): JsonObject = buildJsonObject {
            put("fingerprint", report.fingerprint)
            put("sdk_int", report.sdkInt)
            put("probe_version", report.probeVersion)
            put(
                "checks",
                buildJsonArray {
                    for (check in report.checks) {
                        add(
                            buildJsonObject {
                                put("name", check.name)
                                put("outcome", check.outcome.name)
                                put("detail", check.detail)
                                put("duration_nanos", check.durationNanos)
                            }
                        )
                    }
                },
            )
            val facts = report.facts
            put(
                "facts",
                buildJsonObject {
                    put("front_camera", facts.frontCamera)
                    put("avc_encoder", facts.avcEncoder)
                    put("low_ram_device", facts.lowRamDevice)
                    put("total_mem_bytes", facts.totalMemBytes)
                    put("legacy_camera", facts.legacyCamera)
                    put("egl_recordable", facts.eglRecordable)
                },
            )
        }

        fun decode(json: JsonObject): ProbeReport {
            val facts = json.getValue("facts").jsonObject
            return ProbeReport(
                fingerprint = json.string("fingerprint"),
                sdkInt = json.getValue("sdk_int").jsonPrimitive.int,
                probeVersion = json.getValue("probe_version").jsonPrimitive.int,
                checks =
                    (json.getValue("checks") as JsonArray).map {
                        val check = it.jsonObject
                        ProbeCheck(
                            name = check.string("name"),
                            outcome = CheckOutcome.valueOf(check.string("outcome")),
                            detail = check.string("detail"),
                            durationNanos = check["duration_nanos"]?.jsonPrimitive?.longOrNull,
                        )
                    },
                facts =
                    DeviceFacts(
                        frontCamera = facts.flag("front_camera"),
                        avcEncoder = facts.flag("avc_encoder"),
                        lowRamDevice = facts.flag("low_ram_device"),
                        totalMemBytes = facts.getValue("total_mem_bytes").jsonPrimitive.long,
                        legacyCamera = facts.flag("legacy_camera"),
                        eglRecordable = facts.flag("egl_recordable"),
                    ),
            )
        }

        private fun JsonObject.string(name: String): String =
            (getValue(name) as JsonPrimitive).also { require(it.isString) }.content

        private fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean
    }
}
