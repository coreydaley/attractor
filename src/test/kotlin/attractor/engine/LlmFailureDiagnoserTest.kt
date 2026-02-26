package attractor.engine

import attractor.llm.Client
import attractor.llm.FinishReason
import attractor.llm.LlmResponse
import attractor.llm.Message
import attractor.llm.Request
import attractor.llm.StreamEvent
import attractor.llm.Usage
import attractor.llm.adapters.ProviderAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Builds a fake ProviderAdapter that returns the given canned text as an LLM response.
 * Used to exercise LlmFailureDiagnoser.analyze() without network calls.
 */
private fun fakeClient(responseText: String): Client {
    val adapter = object : ProviderAdapter {
        override val name = "fake"
        override fun complete(request: Request): LlmResponse = LlmResponse(
            id = "test-id",
            model = "fake-model",
            provider = "fake",
            message = Message.assistant(responseText),
            finishReason = FinishReason("stop"),
            usage = Usage.empty()
        )
        override fun stream(request: Request): Sequence<StreamEvent> = emptySequence()
    }
    return Client(mapOf("fake" to adapter), defaultProvider = "fake")
}

private fun makeFailureContext(nodeId: String = "test-node"): FailureContext = FailureContext(
    nodeId = nodeId,
    stageName = "Test Stage",
    stageIndex = 0,
    failureReason = "Something went wrong",
    logsRoot = "/tmp/nonexistent-test-logs",
    contextSnapshot = emptyMap()
)

class LlmFailureDiagnoserTest : FunSpec({

    test("NullFailureDiagnoser returns ABORT with strategy=ABORT and recoverable=false") {
        val diagnoser = NullFailureDiagnoser()
        val result = diagnoser.analyze(makeFailureContext())

        result.strategy shouldBe "ABORT"
        result.recoverable shouldBe false
    }

    test("NullFailureDiagnoser custom reason string is preserved in explanation") {
        val diagnoser = NullFailureDiagnoser("custom reason for test")
        val result = diagnoser.analyze(makeFailureContext())

        result.explanation shouldBe "custom reason for test"
    }

    test("LlmFailureDiagnoser: valid JSON response parses to correct DiagnosisResult") {
        val validJson = """{"recoverable":true,"strategy":"RETRY_WITH_HINT","explanation":"API overloaded","repairHint":"Use a shorter prompt"}"""
        val client = fakeClient(validJson)
        val diagnoser = LlmFailureDiagnoser(client)

        val result = diagnoser.analyze(makeFailureContext())

        result.recoverable shouldBe true
        result.strategy shouldBe "RETRY_WITH_HINT"
        result.explanation shouldBe "API overloaded"
        result.repairHint shouldBe "Use a shorter prompt"
    }

    test("LlmFailureDiagnoser: JSON embedded in LLM preamble text is extracted correctly") {
        val withPreamble = "Here is my diagnosis:\n\n" +
            """{"recoverable":false,"strategy":"ABORT","explanation":"Deterministic failure","repairHint":null}"""
        val client = fakeClient(withPreamble)
        val diagnoser = LlmFailureDiagnoser(client)

        val result = diagnoser.analyze(makeFailureContext())

        result.recoverable shouldBe false
        result.strategy shouldBe "ABORT"
        result.explanation shouldBe "Deterministic failure"
    }

    test("LlmFailureDiagnoser: completely invalid/non-JSON text produces ABORT fallback") {
        val garbage = "I could not analyze this failure. Please check logs manually."
        val client = fakeClient(garbage)
        val diagnoser = LlmFailureDiagnoser(client)

        val result = diagnoser.analyze(makeFailureContext())

        result.strategy shouldBe "ABORT"
        result.recoverable shouldBe false
    }
})
