@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.encode.PPS
import dev.zakadi.sdk.encode.SPS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Log format 1 as Z-064's Spec fixes it: every line kind with its keys in order, `null` where
 * Android cannot read a value, and ASCII JSON throughout.
 */
class LogLinesTest {
    private fun assertLine(expected: String, line: String) {
        assertEquals(expected, line)
        val parsed = Json.parseToJsonElement(line).jsonObject
        assertEquals(LogReader.KEYS.getValue(parsed.kind), parsed.keys.toList())
    }

    @Test
    fun theDeviceLine() {
        assertLine(
            """{"v":1,"kind":"device","t_us":0,"wall_ms":1790000000000,"platform":"android",""" +
                """"phone":"TECNO TECNO KI5k","soc":"Mediatek MT6769","os":"14",""" +
                """"os_build":"zakadi/test/fake:14/UP1A/1:user/release-keys","schedule":1,""" +
                """"args":{"encoder":"hardware","path":"A","divisor":1,"caps":null},"tier":"S",""" +
                """"low_ram":false,"mem_mb":3072,"camera_level":"LIMITED","egl_recordable":true,""" +
                """"front_camera":true,"configure_ms":87.5,"encoders":[{"name":"c2.fake.avc.encoder",""" +
                """"hw":true,"vendor":true,"alias":false,"cbr":true,"cb":true,"max_level":"4.1",""" +
                """"rungs":[0,1,2,3,4],"kbps_range":[1,20000],"achievable_fps":[24,120]}],""" +
                """"thermal":"nominal","battery_pct":81,"charging":false}""",
            LogLines.device(device(), 1, ProbeArgs().json(null)),
        )
    }

    @Test
    fun theDeviceLineWritesNullWhereTheValueCannotBeRead() {
        val encoder =
            FAKE_ENCODER.copy(vendor = null, alias = null, maxLevel = null, achievableFps = null)
        val facts =
            device(report(encoderPassed = false))
                .copy(
                    encoders = listOf(encoder),
                    thermal = null,
                    batteryPct = null,
                    charging = null,
                )
        val line = LogLines.device(facts, 1, ProbeArgs().json(null))
        assertEquals(
            """{"v":1,"kind":"device","t_us":0,"wall_ms":1790000000000,"platform":"android",""" +
                """"phone":"TECNO TECNO KI5k","soc":"Mediatek MT6769","os":"14",""" +
                """"os_build":"zakadi/test/fake:14/UP1A/1:user/release-keys","schedule":1,""" +
                """"args":{"encoder":"hardware","path":"A","divisor":1,"caps":null},"tier":"U",""" +
                """"low_ram":false,"mem_mb":3072,"camera_level":"LIMITED","egl_recordable":true,""" +
                """"front_camera":true,"configure_ms":null,"encoders":[{"name":"c2.fake.avc.encoder",""" +
                """"hw":true,"vendor":null,"alias":null,"cbr":true,"cb":true,"max_level":null,""" +
                """"rungs":[0,1,2,3,4],"kbps_range":[1,20000],"achievable_fps":null}],""" +
                """"thermal":null,"battery_pct":null,"charging":null}""",
            line,
        )
    }

    @Test
    fun theRunLine() {
        val run =
            RunLine(
                run = 2,
                mode = "constant",
                rung = 2,
                w = 480,
                h = 640,
                fps = 15,
                kbps = 400,
                gopMs = 2000,
                path = "A",
                encoder = "c2.fake.avc.encoder",
                hw = true,
                profile = "constrained_baseline",
                level = "3.1",
                bitrateMode = "cbr",
                dropped = emptyList(),
                outFormat = linkedMapOf("bitrate" to "400000", "mime" to "video/avc"),
                camera = CameraLine(640, 480, 270, 15, 30, 1.0 / 3, "boottime"),
                t0Us = 1_234_567,
                thermal = "nominal",
            )
        assertLine(
            """{"v":1,"kind":"run","t_us":2000,"run":2,"mode":"constant","rung":2,"w":480,""" +
                """"h":640,"fps":15,"kbps":400,"gop_ms":2000,"path":"A",""" +
                """"encoder":"c2.fake.avc.encoder","hw":true,"profile":"constrained_baseline",""" +
                """"level":"3.1","bitrate_mode":"cbr","dropped":[],""" +
                """"out_format":{"bitrate":"400000","mime":"video/avc"},"camera":{"w":640,""" +
                """"h":480,"rotation":270,"fps_min":15,"fps_max":30,"ev":0.333,""" +
                """"clock":"boottime"},"t0_us":1234567,"thermal":"nominal"}""",
            LogLines.run(2000, run),
        )
        val refused =
            run.copy(
                profile = null,
                level = null,
                bitrateMode = null,
                dropped = listOf("latency", "profile", "level", "bitrate-mode"),
                camera = CameraLine(480, 640, 90, null, null, null, "monotonic"),
                t0Us = null,
                thermal = null,
            )
        assertLine(
            """{"v":1,"kind":"run","t_us":2000,"run":2,"mode":"constant","rung":2,"w":480,""" +
                """"h":640,"fps":15,"kbps":400,"gop_ms":2000,"path":"A",""" +
                """"encoder":"c2.fake.avc.encoder","hw":true,"profile":null,"level":null,""" +
                """"bitrate_mode":null,"dropped":["latency","profile","level","bitrate-mode"],""" +
                """"out_format":{"bitrate":"400000","mime":"video/avc"},"camera":{"w":480,""" +
                """"h":640,"rotation":90,"fps_min":null,"fps_max":null,"ev":null,""" +
                """"clock":"monotonic"},"t0_us":null,"thermal":null}""",
            LogLines.run(2000, refused),
        )
    }

    @Test
    fun theFrameLines() {
        assertLine(
            """{"v":1,"kind":"params","t_us":2100,"run":2,"source":"csd",""" +
                """"sps":"6742e01fda01e008","pps":"68ce3c80","codec":"avc1.42E01F"}""",
            LogLines.params(2100, 2, "csd", SPS, PPS, "avc1.42E01F"),
        )
        assertLine(
            """{"v":1,"kind":"params","t_us":2100,"run":2,"source":"format","sps":null,""" +
                """"pps":"68ce3c80","codec":null}""",
            LogLines.params(2100, 2, "format", null, PPS, null),
        )
        assertLine(
            """{"v":1,"kind":"in","t_us":2200,"run":2,"pts_us":66666}""",
            LogLines.input(2200, 2, 66_666),
        )
        val out = OutLine(2, 66_666, 1234, true, false, false, 1, listOf(5))
        assertLine(
            """{"v":1,"kind":"out","t_us":2300,"run":2,"pts_us":66666,"bytes":1234,"key":true,""" +
                """"flag_key":false,"param_sets":false,"sc3":1,"nal":[5]}""",
            LogLines.output(2300, out),
        )
        assertLine(
            """{"v":1,"kind":"out","t_us":2300,"run":2,"pts_us":66666,"bytes":1234,"key":true,""" +
                """"flag_key":false,"param_sets":false,"sc3":null,"nal":[5]}""",
            LogLines.output(2300, out.copy(sc3 = null)),
        )
        assertLine(
            """{"v":1,"kind":"kf_req","t_us":2400,"run":2,"n":1,"repeat":false}""",
            LogLines.keyframeRequest(2400, 2, 1, false),
        )
        assertLine(
            """{"v":1,"kind":"rate","t_us":2500,"run":5,"kbps":200}""",
            LogLines.rate(2500, 5, 200),
        )
    }

    @Test
    fun theTickRunEndAndEndLines() {
        val counts = TickCounts(30, 15, 15, 16, 0, 402.5, "nominal", 40)
        assertLine(
            """{"v":1,"kind":"tick","t_us":1002000,"run":2,"captured":30,"submitted":15,""" +
                """"encoded":15,"pre_encode_drops":16,"enc_queue":0,"encoded_kbps":402.5,""" +
                """"thermal":"nominal","cpu_ms":40,"enc_dropped":null}""",
            LogLines.tick(1_002_000, 2, counts),
        )
        assertLine(
            """{"v":1,"kind":"tick","t_us":1002000,"run":2,"captured":30,"submitted":15,""" +
                """"encoded":15,"pre_encode_drops":16,"enc_queue":0,"encoded_kbps":402.5,""" +
                """"thermal":null,"cpu_ms":null,"enc_dropped":null}""",
            LogLines.tick(1_002_000, 2, counts.copy(thermal = null, cpuMs = null)),
        )
        val totals = RunTotals(900, 900, 31, 0, ProbeSummary(398.25, 21.5, 1.0, 14), null)
        assertLine(
            """{"v":1,"kind":"run_end","t_us":60002000,"run":2,"in":900,"out":900,"idr":31,""" +
                """"idr_bare":0,"delivered_kbps":398.25,"kf_latency_ms":21.5,"kf_frames":1,""" +
                """"min_fps":14,"error":null}""",
            LogLines.runEnd(60_002_000, 2, totals),
        )
        assertLine(
            """{"v":1,"kind":"run_end","t_us":60002000,"run":2,"in":0,"out":0,"idr":0,""" +
                """"idr_bare":0,"delivered_kbps":null,"kf_latency_ms":null,"kf_frames":null,""" +
                """"min_fps":null,"error":"timeout"}""",
            LogLines.runEnd(
                60_002_000,
                2,
                RunTotals(0, 0, 0, 0, ProbeSummary.of(emptyList()), "timeout"),
            ),
        )
        assertLine(
            """{"v":1,"kind":"end","t_us":160000000,"runs":6,"reason":"done",""" +
                """"thermal":"nominal","battery_pct":null}""",
            LogLines.end(160_000_000, 6, EndReason.DONE, "nominal", null),
        )
        assertEquals(
            listOf("done", "unsupported_device", "error"),
            EndReason.entries.map { it.wire },
        )
    }

    @Test
    fun valuesAreWrittenAsAscii() {
        assertEquals(
            "\"a\\\"b\\\\c\\nd\\u00e9\\ud83d\\ude00\\u0007\"",
            LogJson.quote("a\"b\\c\nd\u00e9\uD83D\uDE00\u0007"),
        )
        assertEquals(
            listOf("12.346", "400", "0", "null", "0.1", "10000000", "162.712", "-2.5"),
            listOf(12.3456, 400.0, -0.0001, Double.NaN, 0.1, 1e7, 480000 * 1000.0 / 2950000, -2.5)
                .map(LogJson::number),
        )
        assertEquals(
            """{"a":[1,2.5,null,true],"b":{"c":"d"},"e":9000000000}""",
            LogJson.encode(
                linkedMapOf(
                    "a" to listOf(1, 2.5, null, true),
                    "b" to mapOf("c" to "d"),
                    "e" to 9_000_000_000L,
                )
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { LogJson.encode(Any()) }
    }
}
