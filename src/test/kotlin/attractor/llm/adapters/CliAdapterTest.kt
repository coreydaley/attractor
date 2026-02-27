package attractor.llm.adapters

import attractor.llm.ConfigurationError
import attractor.llm.Message
import attractor.llm.ProviderError
import attractor.llm.Request
import attractor.llm.StreamEventType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

// ── Fake ProcessRunner for testing ───────────────────────────────────────────

class FakeProcess(
    private val stdoutData: ByteArray = ByteArray(0),
    private val stderrData: ByteArray = ByteArray(0),
    private val exitCode: Int = 0
) : Process() {
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(stdoutData)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(stderrData)
    override fun waitFor(): Int = exitCode
    override fun exitValue(): Int = exitCode
    override fun destroy() {}
}

class FailingProcessRunner(private val msg: String = "No such file") : ProcessRunner {
    override fun start(args: List<String>): Process = throw java.io.IOException(msg)
}

class FakeProcessRunner(
    private val stdout: String = "",
    private val stderr: String = "",
    private val exitCode: Int = 0
) : ProcessRunner {
    var lastArgs: List<String> = emptyList()
    override fun start(args: List<String>): Process {
        lastArgs = args
        return FakeProcess(stdout.toByteArray(), stderr.toByteArray(), exitCode)
    }
}

// ── buildArgs + buildPromptText tests ────────────────────────────────────────

class CliAdapterTest : FunSpec({

    fun makeRequest(userText: String, systemText: String? = null): Request {
        val msgs = mutableListOf<Message>()
        if (systemText != null) msgs.add(Message.system(systemText))
        msgs.add(Message.user(userText))
        return Request(model = "test-model", messages = msgs)
    }

    // ── buildArgs ─────────────────────────────────────────────────────────────

    test("buildArgs substitutes {prompt} token") {
        val req = makeRequest("hello world")
        val args = buildArgs("claude -p {prompt}", req)
        args shouldBe listOf("claude", "-p", "hello world")
    }

    test("buildArgs appends prompt when no {prompt} token") {
        val req = makeRequest("hello world")
        val args = buildArgs("claude -p", req)
        args shouldBe listOf("claude", "-p", "hello world")
    }

    test("buildArgs includes system message in prompt text") {
        val req = makeRequest("user message", "system instructions")
        val args = buildArgs("claude -p {prompt}", req)
        args[2] shouldContain "system instructions"
        args[2] shouldContain "user message"
    }

    // ── stream: success ───────────────────────────────────────────────────────

    test("AnthropicCliAdapter stream emits TEXT_DELTA events") {
        val runner = FakeProcessRunner(stdout = "Hello from claude")
        val adapter = AnthropicCliAdapter("claude -p {prompt}", runner)
        val req = makeRequest("test")

        val events = adapter.stream(req).toList()
        val deltas = events.filter { it.type == StreamEventType.TEXT_DELTA }.mapNotNull { it.delta }
        deltas.joinToString("") shouldBe "Hello from claude"
    }

    test("AnthropicCliAdapter stream yields STREAM_START") {
        val runner = FakeProcessRunner(stdout = "hi")
        val adapter = AnthropicCliAdapter("claude -p {prompt}", runner)
        val events = adapter.stream(makeRequest("x")).toList()
        events.any { it.type == StreamEventType.STREAM_START } shouldBe true
    }

    test("AnthropicCliAdapter stream yields FINISH on success") {
        val runner = FakeProcessRunner(stdout = "result")
        val adapter = AnthropicCliAdapter("claude -p {prompt}", runner)
        val events = adapter.stream(makeRequest("x")).toList()
        events.any { it.type == StreamEventType.FINISH } shouldBe true
    }

    // ── stream: error cases ───────────────────────────────────────────────────

    test("AnthropicCliAdapter stream yields ERROR on non-zero exit") {
        val runner = FakeProcessRunner(stdout = "partial", stderr = "something went wrong", exitCode = 1)
        val adapter = AnthropicCliAdapter("claude -p {prompt}", runner)
        val events = adapter.stream(makeRequest("test")).toList()
        events.any { it.type == StreamEventType.ERROR } shouldBe true
        val err = events.first { it.type == StreamEventType.ERROR }.error
        err?.message shouldContain "1"
    }

    test("AnthropicCliAdapter stream yields ERROR when binary missing") {
        val runner = FailingProcessRunner("No such file or directory")
        val adapter = AnthropicCliAdapter("nonexistent-binary -p {prompt}", runner)
        val events = adapter.stream(makeRequest("test")).toList()
        events.any { it.type == StreamEventType.ERROR } shouldBe true
        val err = events.first { it.type == StreamEventType.ERROR }.error
        err?.message shouldContain "nonexistent-binary"
    }

    // ── complete ──────────────────────────────────────────────────────────────

    test("AnthropicCliAdapter complete collects full text") {
        val runner = FakeProcessRunner(stdout = "The answer is 42")
        val adapter = AnthropicCliAdapter("claude -p {prompt}", runner)
        val result = adapter.complete(makeRequest("what is the answer"))
        result.text shouldBe "The answer is 42"
        result.provider shouldBe "anthropic"
    }

    test("AnthropicCliAdapter complete throws ProviderError on non-zero exit") {
        val runner = FakeProcessRunner(stdout = "", stderr = "rate limit", exitCode = 1)
        val adapter = AnthropicCliAdapter("claude -p {prompt}", runner)
        shouldThrow<ProviderError> {
            adapter.complete(makeRequest("test"))
        }
    }

    // ── OpenAICliAdapter ──────────────────────────────────────────────────────

    test("OpenAICliAdapter stream emits TEXT_DELTA events") {
        val runner = FakeProcessRunner(stdout = "codex response")
        val adapter = OpenAICliAdapter("codex -p {prompt}", runner)
        val events = adapter.stream(makeRequest("test")).toList()
        val text = events.filter { it.type == StreamEventType.TEXT_DELTA }.mapNotNull { it.delta }.joinToString("")
        text shouldBe "codex response"
    }

    // ── GeminiCliAdapter ──────────────────────────────────────────────────────

    test("GeminiCliAdapter stream emits TEXT_DELTA events") {
        val runner = FakeProcessRunner(stdout = "gemini response")
        val adapter = GeminiCliAdapter("gemini -p {prompt}", runner)
        val events = adapter.stream(makeRequest("test")).toList()
        val text = events.filter { it.type == StreamEventType.TEXT_DELTA }.mapNotNull { it.delta }.joinToString("")
        text shouldBe "gemini response"
    }

    // ── args verification ─────────────────────────────────────────────────────

    test("AnthropicCliAdapter passes correct args to runner") {
        val runner = FakeProcessRunner(stdout = "ok")
        val adapter = AnthropicCliAdapter("claude -p {prompt}", runner)
        adapter.complete(makeRequest("my prompt"))
        runner.lastArgs[0] shouldBe "claude"
        runner.lastArgs[1] shouldBe "-p"
        runner.lastArgs[2] shouldContain "my prompt"
    }
})
