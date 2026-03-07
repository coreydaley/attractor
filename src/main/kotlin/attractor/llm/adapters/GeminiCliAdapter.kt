package attractor.llm.adapters

import attractor.llm.*
import java.io.IOException
import java.util.UUID

/**
 * Google Gemini CLI-backed ProviderAdapter.
 * Invokes the `gemini` CLI binary (or a custom command template) via ProcessBuilder.
 * The command template supports `{prompt}` and optionally `{model}` substitution tokens.
 * By default the model is omitted, letting the CLI tool use its own configured default.
 * Example template: "gemini --yolo -p {prompt}"
 */
class GeminiCliAdapter(
    private val commandTemplate: String = "gemini --yolo -p {prompt}",
    private val runner: ProcessRunner = DefaultProcessRunner
) : ProviderAdapter {

    override val name = "gemini"

    override fun initialize() {
        val binary = commandTemplate.trim().split("\\s+".toRegex()).firstOrNull() ?: "gemini"
        try {
            val proc = ProcessBuilder(binary, "--version")
                .redirectErrorStream(true)
                .start()
            val exit = proc.waitFor()
            if (exit != 0) throw ConfigurationError(
                "CLI binary '$binary' returned exit code $exit. Install it or switch to API mode in Settings."
            )
        } catch (e: IOException) {
            throw ConfigurationError(
                "CLI binary '$binary' not found. Install it or add it to PATH, or switch to API mode in Settings."
            )
        }
    }

    override fun complete(request: Request): LlmResponse {
        val fullText = StringBuilder()
        for (event in stream(request)) {
            if (event.type == StreamEventType.TEXT_DELTA && event.delta != null) {
                fullText.append(event.delta)
            }
            if (event.type == StreamEventType.ERROR && event.error != null) throw event.error
        }
        val msg = Message.assistant(fullText.toString())
        return LlmResponse(
            id = UUID.randomUUID().toString(),
            model = request.model,
            provider = "gemini",
            message = msg,
            finishReason = FinishReason("stop", "end"),
            usage = Usage.empty()
        )
    }

    override fun stream(request: Request): Sequence<StreamEvent> {
        val args = buildArgs(commandTemplate, request)
        return sequence {
            val proc = try {
                runner.start(args)
            } catch (e: IOException) {
                val binary = commandTemplate.trim().split("\\s+".toRegex()).firstOrNull() ?: "gemini"
                yield(StreamEvent(StreamEventType.ERROR,
                    error = ConfigurationError("CLI binary '$binary' not found. Install it or add it to PATH, or switch to API mode in Settings.")))
                return@sequence
            }

            yield(StreamEvent(StreamEventType.STREAM_START))

            val reader = proc.inputStream.bufferedReader()
            try {
                val buf = CharArray(256)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    val chunk = String(buf, 0, n)
                    yield(StreamEvent(StreamEventType.TEXT_DELTA, delta = chunk))
                }
            } finally {
                reader.close()
            }

            val exit = proc.waitFor()
            if (exit != 0) {
                val stderr = proc.errorStream.bufferedReader().readText().take(2048)
                yield(StreamEvent(StreamEventType.ERROR,
                    error = ProviderError("CLI exited with code $exit: $stderr", "gemini")))
                return@sequence
            }

            val finalMsg = Message.assistant("")
            val finalResponse = LlmResponse(
                id = UUID.randomUUID().toString(),
                model = request.model,
                provider = "gemini",
                message = finalMsg,
                finishReason = FinishReason("stop", "end"),
                usage = Usage.empty()
            )
            yield(StreamEvent(StreamEventType.FINISH,
                finishReason = FinishReason("stop", "end"),
                response = finalResponse))
        }
    }
}
