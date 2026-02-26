package attractor.lint

enum class Severity { ERROR, WARNING, INFO }

data class Diagnostic(
    val rule: String,
    val severity: Severity,
    val message: String,
    val nodeId: String? = null,
    val edge: Pair<String, String>? = null,
    val fix: String = ""
)

class ValidationException(
    message: String,
    val errors: List<Diagnostic>
) : Exception(message)
