package attractor.cli

object Formatter {

    private const val MAX_CELL_WIDTH = 40

    fun printTable(headers: List<String>, rows: List<List<String>>) {
        if (headers.isEmpty()) return
        // Compute column widths
        val widths = headers.indices.map { i ->
            val headerLen = headers[i].length
            val maxDataLen = rows.maxOfOrNull { row ->
                truncate(row.getOrElse(i) { "" }).length
            } ?: 0
            maxOf(headerLen, maxDataLen)
        }
        val header = headers.indices.joinToString("  ") { i ->
            headers[i].padEnd(widths[i])
        }
        val separator = widths.joinToString("  ") { "-".repeat(it) }
        println(header)
        println(separator)
        for (row in rows) {
            val line = headers.indices.joinToString("  ") { i ->
                truncate(row.getOrElse(i) { "" }).padEnd(widths[i])
            }
            println(line)
        }
    }

    fun printJson(json: String) {
        println(json)
    }

    fun printLine(text: String) {
        println(text)
    }

    fun printError(message: String) {
        System.err.println("Error: $message")
    }

    private fun truncate(s: String): String =
        if (s.length > MAX_CELL_WIDTH) s.take(MAX_CELL_WIDTH - 3) + "..." else s
}
