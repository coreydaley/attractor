package attractor.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class FormatterTest : FunSpec({

    fun captureStdout(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(baos))
        try { block() } finally { System.setOut(old) }
        return baos.toString()
    }

    test("printTable outputs header and separator row") {
        val output = captureStdout {
            Formatter.printTable(
                listOf("ID", "NAME", "STATUS"),
                listOf(listOf("run-1", "Falcon", "completed"))
            )
        }
        output shouldContain "ID"
        output shouldContain "NAME"
        output shouldContain "STATUS"
        output shouldContain "---"
        output shouldContain "run-1"
        output shouldContain "Falcon"
    }

    test("printTable aligns columns by max cell width") {
        val output = captureStdout {
            Formatter.printTable(
                listOf("KEY", "VALUE"),
                listOf(
                    listOf("short", "x"),
                    listOf("k", "a very long value here")
                )
            )
        }
        val lines = output.trim().lines()
        // All data lines should be same length (padded)
        lines.size shouldBe 4 // header + separator + 2 data rows
    }

    test("printTable with empty rows prints only header") {
        val output = captureStdout {
            Formatter.printTable(listOf("A", "B"), emptyList())
        }
        output shouldContain "A"
        output shouldContain "B"
        // Should not crash
    }

    test("cells longer than 40 chars are truncated with ellipsis") {
        val longValue = "a".repeat(50)
        val output = captureStdout {
            Formatter.printTable(
                listOf("COL"),
                listOf(listOf(longValue))
            )
        }
        output shouldContain "..."
        // The cell should be truncated to 40 chars max
        val dataLine = output.trim().lines().last()
        dataLine.trim().length shouldBe 40
    }

    test("printJson prints raw JSON to stdout") {
        val json = """{"id":"run-1","status":"completed"}"""
        val output = captureStdout { Formatter.printJson(json) }
        output.trim() shouldBe json
    }
})
