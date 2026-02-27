package attractor.cli

/** Thrown when the CLI encounters a runtime or usage error. */
class CliException(message: String, val exitCode: Int = 1) : Exception(message)
