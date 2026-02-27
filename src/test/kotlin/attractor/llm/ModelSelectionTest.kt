package attractor.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ModelSelectionTest : FunSpec({

    fun config(
        mode: ExecutionMode = ExecutionMode.API,
        anthropic: Boolean = true,
        openai: Boolean = true,
        gemini: Boolean = true
    ) = LlmExecutionConfig(
        mode = mode,
        providerToggles = ProviderToggles(anthropic = anthropic, openai = openai, gemini = gemini),
        cliCommands = CliCommands(
            anthropic = "claude -p {prompt}",
            openai = "codex -p {prompt}",
            gemini = "gemini -p {prompt}"
        )
    )

    // ── API mode ──────────────────────────────────────────────────────────────

    test("API mode: anthropic wins when key is present") {
        val env = mapOf("ANTHROPIC_API_KEY" to "sk-test", "OPENAI_API_KEY" to "sk-oai")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env)
        provider shouldBe "anthropic"
        model shouldBe "claude-sonnet-4-6"
    }

    test("API mode: openai wins when anthropic has no key") {
        val env = mapOf("OPENAI_API_KEY" to "sk-oai", "GEMINI_API_KEY" to "gem-key")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env)
        provider shouldBe "openai"
        model shouldBe "gpt-5.2-mini"
    }

    test("API mode: gemini wins when only gemini key present") {
        val env = mapOf("GEMINI_API_KEY" to "gem-key")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env)
        provider shouldBe "gemini"
        model shouldBe "gemini-3-flash-preview"
    }

    test("API mode: GOOGLE_API_KEY used as gemini fallback") {
        val env = mapOf("GOOGLE_API_KEY" to "google-key")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env)
        provider shouldBe "gemini"
        model shouldBe "gemini-3-flash-preview"
    }

    test("API mode: anthropic disabled, openai selected despite anthropic key") {
        val env = mapOf("ANTHROPIC_API_KEY" to "sk-test", "OPENAI_API_KEY" to "sk-oai")
        val cfg = config(mode = ExecutionMode.API, anthropic = false)
        val (provider, _) = ModelSelection.selectModel(cfg, env)
        provider shouldBe "openai"
    }

    test("API mode: all disabled throws ConfigurationError") {
        val env = mapOf("ANTHROPIC_API_KEY" to "sk-test")
        val cfg = config(mode = ExecutionMode.API, anthropic = false, openai = false, gemini = false)
        shouldThrow<ConfigurationError> {
            ModelSelection.selectModel(cfg, env)
        }
    }

    test("API mode: no keys throws ConfigurationError") {
        val (_, env) = emptyMap<String, String>() to emptyMap<String, String>()
        shouldThrow<ConfigurationError> {
            ModelSelection.selectModel(config(mode = ExecutionMode.API), env)
        }
    }

    // ── CLI mode ──────────────────────────────────────────────────────────────

    test("CLI mode: anthropic selected without API key") {
        val env = emptyMap<String, String>()
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.CLI), env)
        provider shouldBe "anthropic"
        model shouldBe "claude-sonnet-4-6"
    }

    test("CLI mode: anthropic disabled, openai selected") {
        val env = emptyMap<String, String>()
        val cfg = config(mode = ExecutionMode.CLI, anthropic = false)
        val (provider, _) = ModelSelection.selectModel(cfg, env)
        provider shouldBe "openai"
    }

    test("CLI mode: only gemini enabled") {
        val env = emptyMap<String, String>()
        val cfg = config(mode = ExecutionMode.CLI, anthropic = false, openai = false)
        val (provider, model) = ModelSelection.selectModel(cfg, env)
        provider shouldBe "gemini"
        model shouldBe "gemini-3-flash-preview"
    }

    test("CLI mode: all disabled throws ConfigurationError") {
        val env = emptyMap<String, String>()
        val cfg = config(mode = ExecutionMode.CLI, anthropic = false, openai = false, gemini = false)
        shouldThrow<ConfigurationError> {
            ModelSelection.selectModel(cfg, env)
        }
    }
})
