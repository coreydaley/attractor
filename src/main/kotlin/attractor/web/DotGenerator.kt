package attractor.web

import attractor.db.RunStore
import attractor.llm.ClientProvider
import attractor.llm.ConfigurationError
import attractor.llm.LlmExecutionConfig
import attractor.llm.Message
import attractor.llm.ModelSelection
import attractor.llm.Request
import attractor.llm.StreamEventType
import attractor.llm.generate

class DotGenerator(private val store: RunStore) {

    private val SYSTEM_PROMPT = """
You are an expert at writing Attractor project files in Graphviz DOT format.
Attractor is a DOT-based project runner that orchestrates multi-stage AI workflows.

## Project Structure

Every project is a `digraph` with required graph attributes:
  digraph ProjectName {
    graph [goal="High-level goal description", label="Human-readable name"]
    ...
  }

## Node Types (by shape attribute)

| shape         | purpose                                            |
|---------------|----------------------------------------------------|
| Mdiamond      | Start node — exactly one required                  |
| Msquare       | Exit node — at least one required                  |
| box           | LLM codergen stage (default shape)                 |
| hexagon       | Wait for human approval / input                    |
| diamond       | Conditional branch — routes by edge label          |
| component     | Spawn parallel branches                            |
| tripleoctagon | Fan-in — joins parallel branches back together     |
| parallelogram | Tool / shell execution stage                       |
| house         | Stack manager loop                                 |

## Key Node Attributes

- label       — display name (required on all nodes)
- prompt      — LLM prompt for box nodes; supports ${'$'}variable and ${'$'}goal substitution
- max_retries — integer, how many times to retry on failure (default 3)
- timeout     — duration string e.g. "30s", "5m", "1h"
- goal_gate   — condition expression checked at exit node

## Edge Attributes (for conditional routing out of diamond nodes)

- label     — branch name shown in UI
- condition — expression like "outcome=success", "outcome!=success", "score>80"

## Variable Expansion in Prompts

Use ${'$'}name syntax to reference context values set by previous stages:
- ${'$'}goal         — the project's graph[goal] attribute
- ${'$'}variableName — any key set by a prior stage's output

## Rules

1. Exactly one start node (shape=Mdiamond)
2. At least one exit node (shape=Msquare)
3. All nodes must be reachable; no isolated nodes
4. Conditional (diamond) nodes must have labelled outgoing edges for every branch
5. For parallel execution: component fan-out node → parallel work → tripleoctagon fan-in
6. Keep prompts specific, actionable, and relevant to the project goal
7. Use concise camelCase or snake_case for node IDs (no spaces)
8. Output ONLY the raw DOT source — no markdown fences, no explanations

## Examples

### Simple linear project
digraph WriteTests {
    graph [goal="Write and run unit tests for the codebase", label="Test Writer"]
    start   [shape=Mdiamond, label="Start"]
    analyze [label="Analyze Code", prompt="Analyze the codebase structure and identify testable units for: ${'$'}goal"]
    write   [label="Write Tests", prompt="Write comprehensive unit tests based on the analysis. Goal: ${'$'}goal"]
    run     [label="Run Tests",   prompt="Execute the tests and report results, coverage, and any failures"]
    exit    [shape=Msquare, label="Done"]
    start -> analyze -> write -> run -> exit
}

### Project with conditional retry loop
digraph ValidateFeature {
    graph [goal="Implement and validate a new feature", label="Feature Validation"]
    start     [shape=Mdiamond, label="Start"]
    implement [label="Implement", prompt="Implement: ${'$'}goal"]
    validate  [label="Validate",  prompt="Run tests and validate the implementation thoroughly"]
    check     [shape=diamond,    label="Tests pass?"]
    exit      [shape=Msquare,    label="Done"]
    start -> implement -> validate -> check
    check -> exit      [label="yes", condition="outcome=success"]
    check -> implement [label="no",  condition="outcome!=success"]
}

### Project with human review gate
digraph ContentReview {
    graph [goal="Draft and publish reviewed content", label="Content Review"]
    start  [shape=Mdiamond, label="Start"]
    draft  [label="Draft Content", prompt="Write a draft for: ${'$'}goal"]
    review [shape=hexagon,  label="Human Review", prompt="Please review the draft and approve or request changes"]
    polish [label="Polish",        prompt="Apply reviewer feedback and finalize the content"]
    exit   [shape=Msquare, label="Published"]
    start -> draft -> review -> polish -> exit
}
""".trimIndent()

    private fun config(): LlmExecutionConfig = LlmExecutionConfig.from(store)

    /**
     * Stream-generate an Attractor DOT project file.
     * Calls [onDelta] for each text chunk as it arrives.
     * Returns the final cleaned DOT source (markdown fences stripped).
     */
    fun generateStream(prompt: String, agentId: String = "", modelId: String = "", onDelta: (String) -> Unit): String {
        val cfg = config()
        val client = ClientProvider.getClient(cfg)
        val (provider, model) = ModelSelection.selectModel(cfg, agentId = agentId, modelId = modelId)

        val msgs = mutableListOf<Message>()
        msgs.add(Message.system(SYSTEM_PROMPT))
        msgs.add(Message.user(prompt))

        val request = Request(
            model = model,
            messages = msgs,
            provider = provider,
            maxTokens = 8192,
            temperature = 0.2
        )

        val fullText = StringBuilder()
        for (event in client.stream(request)) {
            when {
                event.type == StreamEventType.TEXT_DELTA && event.delta != null -> {
                    onDelta(event.delta)
                    fullText.append(event.delta)
                }
                event.type == StreamEventType.ERROR && event.error != null ->
                    throw event.error
            }
        }

        if (fullText.isEmpty()) throw ConfigurationError("No output received from provider ($provider). Check the CLI command or API key in Settings.")

        return extractDotSource(fullText.toString())
    }

    /**
     * Generate an Attractor DOT project file from a natural language description.
     * Returns the raw DOT source string.
     */
    fun generate(prompt: String, agentId: String = "", modelId: String = ""): String {
        val cfg = config()
        val client = ClientProvider.getClient(cfg)
        val (provider, model) = ModelSelection.selectModel(cfg, agentId = agentId, modelId = modelId)

        val result = generate(
            model = model,
            system = SYSTEM_PROMPT,
            prompt = prompt,
            maxTokens = 8192,
            temperature = 0.2,
            provider = provider,
            client = client
        )

        return extractDotSource(result.text)
    }

    /**
     * Stream-generate a modified version of an existing Attractor DOT project.
     * [baseDot] is the existing DOT source; [changes] is the natural language modification request.
     * Calls [onDelta] for each text chunk as it arrives.
     * Returns the final cleaned DOT source.
     */
    fun iterateStream(baseDot: String, changes: String, agentId: String = "", modelId: String = "", onDelta: (String) -> Unit): String {
        val iteratePrompt = """Given the following existing Attractor project DOT source:

$baseDot

Modify it according to these instructions: $changes

Output ONLY the modified raw DOT source — no markdown fences, no explanations.
Keep all existing nodes and edges unless explicitly told to remove them.""".trimIndent()

        return generateStream(iteratePrompt, agentId, modelId, onDelta)
    }

    /**
     * Ask the LLM to fix a broken DOT source given the graphviz error.
     * Calls [onDelta] for each text chunk. Returns the cleaned DOT source.
     */
    fun fixStream(brokenDot: String, error: String, agentId: String = "", modelId: String = "", onDelta: (String) -> Unit): String {
        val cfg = config()
        val client = ClientProvider.getClient(cfg)
        val (provider, model) = ModelSelection.selectModel(cfg, agentId = agentId, modelId = modelId)

        val msgs = mutableListOf<Message>()
        msgs.add(Message.system(SYSTEM_PROMPT))
        msgs.add(Message.user("""The following Graphviz DOT project has a syntax error. Fix it so it renders correctly.

Graphviz error:
$error

Broken DOT source:
$brokenDot

Output ONLY the corrected raw DOT source — no markdown fences, no explanations."""))

        val request = Request(
            model = model,
            messages = msgs,
            provider = provider,
            maxTokens = 8192,
            temperature = 0.1
        )

        val fullText = StringBuilder()
        for (event in client.stream(request)) {
            when {
                event.type == StreamEventType.TEXT_DELTA && event.delta != null -> {
                    onDelta(event.delta)
                    fullText.append(event.delta)
                }
                event.type == StreamEventType.ERROR && event.error != null ->
                    throw event.error
            }
        }
        if (fullText.isEmpty()) throw ConfigurationError("No output received from provider ($provider). Check the CLI command or API key in Settings.")
        return extractDotSource(fullText.toString())
    }

    /**
     * Stream a concise natural language description of an existing DOT project.
     * Calls [onDelta] for each text chunk as it arrives.
     * Returns the final description string.
     */
    fun describeStream(dotSource: String, agentId: String = "", modelId: String = "", onDelta: (String) -> Unit): String {
        val cfg = config()
        val client = ClientProvider.getClient(cfg)
        val (provider, model) = ModelSelection.selectModel(cfg, agentId = agentId, modelId = modelId)

        val msgs = mutableListOf<Message>()
        msgs.add(Message.user("""Given the following Attractor project DOT source, write a concise natural language description of what this project does. The description should be 1-3 sentences, specific about the workflow steps and overall goal, and suitable as a prompt to regenerate a similar project.

DOT source:
$dotSource

Output ONLY the description — no labels, no headers, no markdown formatting."""))

        val request = Request(
            model = model,
            messages = msgs,
            provider = provider,
            maxTokens = 512,
            temperature = 0.3
        )

        val fullText = StringBuilder()
        for (event in client.stream(request)) {
            when {
                event.type == StreamEventType.TEXT_DELTA && event.delta != null -> {
                    onDelta(event.delta)
                    fullText.append(event.delta)
                }
                event.type == StreamEventType.ERROR && event.error != null ->
                    throw event.error
            }
        }
        return fullText.toString().trim()
    }

    /** Strip markdown code fences if the model wrapped its output. */
    private fun extractDotSource(text: String): String {
        val fenceRegex = Regex("""```(?:dot|DOT|graphviz)?\s*\n?([\s\S]+?)\n?```""")
        val match = fenceRegex.find(text.trim())
        return if (match != null) match.groupValues[1].trim() else text.trim()
    }
}
