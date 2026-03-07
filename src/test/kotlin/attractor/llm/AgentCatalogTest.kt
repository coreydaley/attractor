package attractor.llm

import attractor.db.SqliteRunStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class AgentCatalogTest : FunSpec({

    fun newStore(): SqliteRunStore {
        val f = Files.createTempFile("agent-catalog-test-", ".db").toFile()
        f.deleteOnExit()
        return SqliteRunStore(f.absolutePath)
    }

    test("catalog contains all five expected agents") {
        val store = newStore()
        val catalog = AgentCatalog.buildCatalog(store)
        val ids = catalog.map { it.id }
        ids.contains("anthropic") shouldBe true
        ids.contains("openai")    shouldBe true
        ids.contains("gemini")    shouldBe true
        ids.contains("copilot")   shouldBe true
        ids.contains("custom")    shouldBe true
        store.close()
    }

    test("copilot is CLI-only: directApiModels=null, cliModels=[copilot]") {
        val store = newStore()
        val catalog = AgentCatalog.buildCatalog(store)
        val copilot = catalog.first { it.id == "copilot" }
        copilot.directApiModels.shouldBeNull()
        copilot.cliModels.shouldNotBeNull()
        copilot.cliModels!! shouldHaveSize 1
        copilot.cliModels!![0].id shouldBe "copilot"
        store.close()
    }

    test("custom is API-only: directApiModels=emptyList, cliModels=null") {
        val store = newStore()
        val catalog = AgentCatalog.buildCatalog(store)
        val custom = catalog.first { it.id == "custom" }
        custom.directApiModels.shouldNotBeNull()
        custom.directApiModels!! shouldHaveSize 0
        custom.cliModels.shouldBeNull()
        store.close()
    }

    test("no DB models: falls back to ModelCatalog built-in list for anthropic") {
        val store = newStore()
        val catalog = AgentCatalog.buildCatalog(store)
        val anthropic = catalog.first { it.id == "anthropic" }
        val expected = ModelCatalog.listModels("anthropic").size
        anthropic.directApiModels.shouldNotBeNull()
        anthropic.directApiModels!! shouldHaveSize expected
        store.close()
    }

    test("DB models override built-in list for anthropic") {
        val store = newStore()
        val customModels = listOf(
            AgentModelInfo("claude-custom-1", "Custom Model 1"),
            AgentModelInfo("claude-custom-2", "Custom Model 2")
        )
        store.setSetting("models_anthropic_json", ModelFetcher.modelsToJson(customModels))

        val catalog = AgentCatalog.buildCatalog(store)
        val anthropic = catalog.first { it.id == "anthropic" }
        anthropic.directApiModels.shouldNotBeNull()
        anthropic.directApiModels!! shouldHaveSize 2
        anthropic.directApiModels!![0].id shouldBe "claude-custom-1"
        anthropic.directApiModels!![1].id shouldBe "claude-custom-2"
        store.close()
    }

    test("invalid JSON in DB falls back to built-in list") {
        val store = newStore()
        store.setSetting("models_openai_json", "not-valid-json{{{")

        val catalog = AgentCatalog.buildCatalog(store)
        val openai = catalog.first { it.id == "openai" }
        val expected = ModelCatalog.listModels("openai").size
        openai.directApiModels.shouldNotBeNull()
        openai.directApiModels!! shouldHaveSize expected
        store.close()
    }

    test("empty JSON array in DB falls back to built-in list") {
        val store = newStore()
        store.setSetting("models_gemini_json", "[]")

        val catalog = AgentCatalog.buildCatalog(store)
        val gemini = catalog.first { it.id == "gemini" }
        val expected = ModelCatalog.listModels("gemini").size
        gemini.directApiModels.shouldNotBeNull()
        gemini.directApiModels!! shouldHaveSize expected
        store.close()
    }

    test("CLI models for anthropic always use built-in list regardless of DB") {
        val store = newStore()
        val customModels = listOf(AgentModelInfo("claude-custom-1", "Custom Model 1"))
        store.setSetting("models_anthropic_json", ModelFetcher.modelsToJson(customModels))

        val catalog = AgentCatalog.buildCatalog(store)
        val anthropic = catalog.first { it.id == "anthropic" }
        val expected = ModelCatalog.listModels("anthropic").size
        anthropic.cliModels.shouldNotBeNull()
        anthropic.cliModels!! shouldHaveSize expected
        store.close()
    }
})
