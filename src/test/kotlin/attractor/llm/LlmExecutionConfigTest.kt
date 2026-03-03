package attractor.llm

import attractor.db.RunStore
import attractor.db.SqliteRunStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class LlmExecutionConfigTest : FunSpec({

    lateinit var store: RunStore
    lateinit var tmpDb: java.io.File

    beforeEach {
        tmpDb = Files.createTempFile("llm-config-test-", ".db").toFile()
        store = SqliteRunStore(tmpDb.absolutePath)
    }

    afterEach {
        store.close()
        tmpDb.delete()
    }

    test("defaults when no settings present") {
        val cfg = LlmExecutionConfig.from(store)
        cfg.mode shouldBe ExecutionMode.API
        cfg.providerToggles.anthropic shouldBe true
        cfg.providerToggles.openai shouldBe true
        cfg.providerToggles.gemini shouldBe true
        cfg.cliCommands.anthropic shouldBe "claude -p {prompt}"
        cfg.cliCommands.openai shouldBe "codex -p {prompt}"
        cfg.cliCommands.gemini shouldBe "gemini -p {prompt}"
    }

    test("execution_mode cli roundtrip") {
        store.setSetting("execution_mode", "cli")
        val cfg = LlmExecutionConfig.from(store)
        cfg.mode shouldBe ExecutionMode.CLI
    }

    test("execution_mode api roundtrip") {
        store.setSetting("execution_mode", "api")
        val cfg = LlmExecutionConfig.from(store)
        cfg.mode shouldBe ExecutionMode.API
    }

    test("unknown execution_mode value defaults to API") {
        store.setSetting("execution_mode", "something_else")
        val cfg = LlmExecutionConfig.from(store)
        cfg.mode shouldBe ExecutionMode.API
    }

    test("provider_anthropic_enabled false") {
        store.setSetting("provider_anthropic_enabled", "false")
        val cfg = LlmExecutionConfig.from(store)
        cfg.providerToggles.anthropic shouldBe false
        cfg.isProviderEnabled("anthropic") shouldBe false
    }

    test("provider_openai_enabled false") {
        store.setSetting("provider_openai_enabled", "false")
        val cfg = LlmExecutionConfig.from(store)
        cfg.providerToggles.openai shouldBe false
        cfg.isProviderEnabled("openai") shouldBe false
    }

    test("provider_gemini_enabled false") {
        store.setSetting("provider_gemini_enabled", "false")
        val cfg = LlmExecutionConfig.from(store)
        cfg.providerToggles.gemini shouldBe false
        cfg.isProviderEnabled("gemini") shouldBe false
    }

    test("isProviderEnabled returns false for unknown provider") {
        val cfg = LlmExecutionConfig.from(store)
        cfg.isProviderEnabled("unknown") shouldBe false
    }

    test("cli command templates roundtrip") {
        store.setSetting("cli_anthropic_command", "my-claude --prompt {prompt}")
        store.setSetting("cli_openai_command", "/usr/local/bin/codex -p {prompt}")
        store.setSetting("cli_gemini_command", "gemini --text {prompt}")
        val cfg = LlmExecutionConfig.from(store)
        cfg.cliCommands.anthropic shouldBe "my-claude --prompt {prompt}"
        cfg.cliCommands.openai shouldBe "/usr/local/bin/codex -p {prompt}"
        cfg.cliCommands.gemini shouldBe "gemini --text {prompt}"
    }

    test("all settings combined roundtrip") {
        store.setSetting("execution_mode", "cli")
        store.setSetting("provider_anthropic_enabled", "true")
        store.setSetting("provider_openai_enabled", "false")
        store.setSetting("provider_gemini_enabled", "false")
        val cfg = LlmExecutionConfig.from(store)
        cfg.mode shouldBe ExecutionMode.CLI
        cfg.isProviderEnabled("anthropic") shouldBe true
        cfg.isProviderEnabled("openai") shouldBe false
        cfg.isProviderEnabled("gemini") shouldBe false
    }
})
