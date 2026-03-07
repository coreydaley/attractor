package attractor.llm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ModelSelectionTest : FunSpec({

    fun config(
        mode: ExecutionMode = ExecutionMode.API,
        anthropic: Boolean = true,
        openai: Boolean = true,
        gemini: Boolean = true,
        copilot: Boolean = false,
        custom: Boolean = false
    ) = LlmExecutionConfig(
        mode = mode,
        providerToggles = ProviderToggles(anthropic = anthropic, openai = openai, gemini = gemini, copilot = copilot, custom = custom),
        cliCommands = CliCommands(
            anthropic = "claude -p {prompt}",
            openai = "codex -p {prompt}",
            gemini = "gemini -p {prompt}",
            copilot = "copilot --allow-all-tools -p {prompt}"
        ),
        customApiConfig = CustomApiConfig(host = "http://localhost", port = "11434", apiKey = "", model = "llama3.2")
    )

    // ── API mode ──────────────────────────────────────────────────────────────

    test("API mode: anthropic wins when key is present") {
        val env = mapOf("ATTRACTOR_ANTHROPIC_API_KEY" to "sk-test", "ATTRACTOR_OPENAI_API_KEY" to "sk-oai")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env)
        provider shouldBe "anthropic"
        model shouldBe "claude-sonnet-4-6"
    }

    test("API mode: openai wins when anthropic has no key") {
        val env = mapOf("ATTRACTOR_OPENAI_API_KEY" to "sk-oai", "ATTRACTOR_GEMINI_API_KEY" to "gem-key")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env)
        provider shouldBe "openai"
        model shouldBe "gpt-4o-mini"
    }

    test("API mode: gemini wins when only gemini key present") {
        val env = mapOf("ATTRACTOR_GEMINI_API_KEY" to "gem-key")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env)
        provider shouldBe "gemini"
        model shouldBe "gemini-2.0-flash"
    }

    test("API mode: ATTRACTOR_GOOGLE_API_KEY used as gemini fallback") {
        val env = mapOf("ATTRACTOR_GOOGLE_API_KEY" to "google-key")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env)
        provider shouldBe "gemini"
        model shouldBe "gemini-2.0-flash"
    }

    test("API mode: anthropic disabled, openai selected despite anthropic key") {
        val env = mapOf("ATTRACTOR_ANTHROPIC_API_KEY" to "sk-test", "ATTRACTOR_OPENAI_API_KEY" to "sk-oai")
        val cfg = config(mode = ExecutionMode.API, anthropic = false)
        val (provider, _) = ModelSelection.selectModel(cfg, env)
        provider shouldBe "openai"
    }

    test("API mode: all disabled throws ConfigurationError") {
        val env = mapOf("ATTRACTOR_ANTHROPIC_API_KEY" to "sk-test")
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
        model shouldBe "gemini-2.0-flash"
    }

    test("CLI mode: all disabled throws ConfigurationError") {
        val env = emptyMap<String, String>()
        val cfg = config(mode = ExecutionMode.CLI, anthropic = false, openai = false, gemini = false)
        shouldThrow<ConfigurationError> {
            ModelSelection.selectModel(cfg, env)
        }
    }

    // ── Explicit agentId override ─────────────────────────────────────────────

    test("explicit agentId selects that provider in API mode with key") {
        val env = mapOf("ATTRACTOR_ANTHROPIC_API_KEY" to "sk-anth", "ATTRACTOR_OPENAI_API_KEY" to "sk-oai")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env, agentId = "openai")
        provider shouldBe "openai"
        model shouldBe "gpt-4o-mini"
    }

    test("explicit agentId + modelId uses provided model") {
        val env = mapOf("ATTRACTOR_ANTHROPIC_API_KEY" to "sk-anth")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env, agentId = "anthropic", modelId = "claude-opus-4-6")
        provider shouldBe "anthropic"
        model shouldBe "claude-opus-4-6"
    }

    test("explicit agentId for disabled provider throws ConfigurationError") {
        val env = mapOf("ATTRACTOR_OPENAI_API_KEY" to "sk-oai")
        val cfg = config(mode = ExecutionMode.API, openai = false)
        shouldThrow<ConfigurationError> {
            ModelSelection.selectModel(cfg, env, agentId = "openai")
        }
    }

    test("explicit agentId in API mode with no key throws ConfigurationError") {
        val env = emptyMap<String, String>()
        shouldThrow<ConfigurationError> {
            ModelSelection.selectModel(config(mode = ExecutionMode.API), env, agentId = "anthropic")
        }
    }

    test("explicit agentId in CLI mode does not require API key") {
        val env = emptyMap<String, String>()
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.CLI), env, agentId = "gemini")
        provider shouldBe "gemini"
        model shouldBe "gemini-2.0-flash"
    }

    test("explicit copilot agentId in CLI mode returns copilot model") {
        val env = emptyMap<String, String>()
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.CLI, copilot = true), env, agentId = "copilot")
        provider shouldBe "copilot"
        model shouldBe "copilot"
    }

    // ── Auto-select with modelId hint ─────────────────────────────────────────

    test("auto-select with modelId hint uses that model for winning provider") {
        val env = mapOf("ATTRACTOR_ANTHROPIC_API_KEY" to "sk-anth")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env, modelId = "claude-haiku-4-5-20251001")
        provider shouldBe "anthropic"
        model shouldBe "claude-haiku-4-5-20251001"
    }

    test("auto-select with empty modelId uses default model") {
        val env = mapOf("ATTRACTOR_ANTHROPIC_API_KEY" to "sk-anth")
        val (provider, model) = ModelSelection.selectModel(config(mode = ExecutionMode.API), env, modelId = "")
        provider shouldBe "anthropic"
        model shouldBe "claude-sonnet-4-6"
    }
})
