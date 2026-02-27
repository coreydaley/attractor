package attractor.cli

enum class OutputFormat { TEXT, JSON }

data class CliContext(
    val baseUrl: String = "http://localhost:8080",
    val outputFormat: OutputFormat = OutputFormat.TEXT
)
