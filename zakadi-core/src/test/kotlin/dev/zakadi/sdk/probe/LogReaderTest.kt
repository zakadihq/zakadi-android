@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader the device test checks a log with: it takes a whole log and refuses a broken one. */
class LogReaderTest {
    private val device = LogLines.device(device(), 1, ProbeArgs().json(null))
    private val run =
        LogLines.run(
            10,
            RunLine(
                0,
                "constant",
                0,
                480,
                640,
                20,
                900,
                2000,
                "A",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            ),
        )
    private val input = LogLines.input(20, 0, 0)
    private val runEnd =
        LogLines.runEnd(30, 0, RunTotals(1, 0, 0, 0, ProbeSummary.of(emptyList()), "timeout"))
    private val end = LogLines.end(40, 1, EndReason.DONE, null, null)

    private fun text(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    private fun refused(text: String): String =
        assertThrows(IllegalStateException::class.java) { LogReader.read(text) }.message!!

    @Test
    fun aWholeLogReadsBack() {
        val lines = LogReader.read(text(device, run, input, runEnd, end))
        assertEquals(listOf("device", "run", "in", "run_end", "end"), lines.map { it.kind })
    }

    @Test
    fun aBrokenLogIsRefusedNamingItsLine() {
        assertTrue(refused(text(device, run, "{\"v\":1", runEnd, end)).startsWith("line 3"))
        assertTrue(
            refused(text(device, run, input.replace("\"pts_us\"", "\"pts\""), runEnd, end))
                .startsWith("line 3: keys")
        )
        assertTrue(refused(text(device, input, run, runEnd, end)).startsWith("line 2: in of run 0"))
        assertTrue(refused(text(device, run, input, end)).contains("no run_end"))
        assertTrue(refused(text(device, run, input, runEnd, runEnd, end)).startsWith("line 5"))
        assertTrue(refused(text(run, device, runEnd, end)).startsWith("line 1"))
        assertTrue(refused(text(device, run, input, runEnd)).contains("not end"))
        assertTrue(
            refused(text(device, run, input, runEnd, end).removeSuffix("\n")).contains("\\n")
        )
        assertTrue(
            refused(text(device, end.replace("\"runs\":1", "\"runs\":2"))).contains("end counts")
        )
        assertTrue(
            refused(
                    text(
                        device,
                        "{\"v\":1,\"kind\":\"end\",\"t_us\":-1,\"runs\":0," +
                            "\"reason\":\"done\",\"thermal\":null,\"battery_pct\":null}",
                    )
                )
                .contains("t_us")
        )
    }
}
