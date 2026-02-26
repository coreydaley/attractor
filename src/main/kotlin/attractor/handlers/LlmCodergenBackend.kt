package attractor.handlers

import attractor.dot.DotNode
import attractor.llm.Client
import attractor.llm.ToolDefinition
import attractor.llm.generate
import attractor.state.Context
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Real LLM backend for CodergenHandler (Section 4.5).
 * Provides workspace tools so the LLM can write files, run commands, and verify output.
 * Writes a live.log to stageDir in real time for each tool call and result.
 */
class LlmCodergenBackend(private val client: Client) : CodergenBackend {

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
        private const val MAX_TOOL_ROUNDS = 15
        private const val COMMAND_TIMEOUT_SEC = 120L
        private const val MAX_OUTPUT_CHARS = 20_000
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    override fun run(node: DotNode, prompt: String, context: Context, workspaceDir: File, stageDir: File): Any {
        val model = node.llmModel.ifEmpty { DEFAULT_MODEL }
        val provider = node.llmProvider.ifEmpty { null }
        val reasoningEffort = node.reasoningEffort.ifEmpty { null }
        // Per-node override: max_tool_rounds=N in the .dot file
        val maxToolRounds = node.attrLong("max_tool_rounds", MAX_TOOL_ROUNDS.toLong()).toInt()

        liveLog(stageDir, "Stage '${node.id}' started — calling LLM ($model)")
        liveLog(stageDir, "Workspace: ${workspaceDir.absolutePath}")

        val system = buildSystemPrompt(workspaceDir)
        val tools = buildWorkspaceTools(workspaceDir, stageDir)

        val result = generate(
            model = model,
            prompt = prompt,
            system = system,
            tools = tools,
            maxToolRounds = maxToolRounds,
            reasoningEffort = reasoningEffort,
            provider = provider,
            client = client
        )

        val summary = result.text.take(300).replace("\n", " ")
        liveLog(stageDir, "Stage complete. Summary: $summary")

        return result.text
    }

    private fun buildSystemPrompt(workspaceDir: File): String = """
You are an expert software engineer executing a stage in an automated pipeline.

Workspace directory: ${workspaceDir.absolutePath}

You have tools to interact with the workspace:
- write_file: Write a file (use relative paths within the workspace)
- read_file: Read a file from the workspace
- run_command: Execute a shell command in the workspace directory
- list_files: List files in the workspace

Guidelines:
- Only use tools when the task explicitly requires creating files, running code, or reading workspace contents.
- For planning, analysis, or design tasks, respond with a clear text answer — do not call tools unnecessarily.
- When asked to write or build code, use write_file to create files and run_command to verify they work.
- If a command fails, diagnose the error, fix it, and try again.
- Complete exactly what the task asks — do not do more than requested.
- When done, provide a concise summary of what was accomplished.
""".trimIndent()

    private fun liveLog(stageDir: File, line: String) {
        val time = LocalTime.now().format(TIME_FMT)
        try { File(stageDir, "live.log").appendText("[$time] $line\n") } catch (_: Exception) {}
    }

    private fun buildWorkspaceTools(workspaceDir: File, stageDir: File): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "write_file",
            description = "Write content to a file in the workspace. Creates parent directories as needed. Use relative paths.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Relative file path within the workspace"),
                    "content" to mapOf("type" to "string", "description" to "File content to write")
                ),
                "required" to listOf("path", "content")
            ),
            execute = { args ->
                val path = args["path"]?.toString() ?: return@ToolDefinition "Error: 'path' is required"
                val content = args["content"]?.toString() ?: return@ToolDefinition "Error: 'content' is required"
                liveLog(stageDir, "→ write_file: $path")
                try {
                    val target = workspaceDir.resolve(path).canonicalFile
                    if (!target.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
                        val err = "Error: path '$path' escapes the workspace directory"
                        liveLog(stageDir, "  ✗ $err")
                        err
                    } else {
                        target.parentFile?.mkdirs()
                        target.writeText(content)
                        val msg = "Wrote ${content.length} bytes to $path"
                        liveLog(stageDir, "  ✓ $msg")
                        msg
                    }
                } catch (e: Exception) {
                    val err = "Error writing file: ${e.message}"
                    liveLog(stageDir, "  ✗ $err")
                    err
                }
            }
        ),

        ToolDefinition(
            name = "read_file",
            description = "Read a file from the workspace. Use relative paths.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Relative file path within the workspace")
                ),
                "required" to listOf("path")
            ),
            execute = { args ->
                val path = args["path"]?.toString() ?: return@ToolDefinition "Error: 'path' is required"
                liveLog(stageDir, "→ read_file: $path")
                try {
                    val target = workspaceDir.resolve(path).canonicalFile
                    if (!target.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
                        "Error: path '$path' escapes the workspace directory"
                    } else if (!target.exists()) {
                        liveLog(stageDir, "  ✗ File not found: $path")
                        "File not found: $path"
                    } else {
                        val text = target.readText()
                        liveLog(stageDir, "  ✓ Read ${text.length} bytes from $path")
                        if (text.length > MAX_OUTPUT_CHARS)
                            text.take(MAX_OUTPUT_CHARS) + "\n... (truncated, ${text.length} total chars)"
                        else text
                    }
                } catch (e: Exception) {
                    "Error reading file: ${e.message}"
                }
            }
        ),

        ToolDefinition(
            name = "run_command",
            description = "Execute a shell command in the workspace directory. Returns combined stdout+stderr and the exit code. Timeout: ${COMMAND_TIMEOUT_SEC}s.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "Shell command to execute")
                ),
                "required" to listOf("command")
            ),
            execute = { args ->
                val command = args["command"]?.toString() ?: return@ToolDefinition "Error: 'command' is required"
                liveLog(stageDir, "→ run_command: $command")
                try {
                    val process = ProcessBuilder("/bin/sh", "-c", command)
                        .directory(workspaceDir)
                        .redirectErrorStream(true)
                        .start()
                    val outputBuf = StringBuilder()
                    val reader = process.inputStream.bufferedReader()
                    val readerThread = Thread {
                        try {
                            reader.lines().forEach { line ->
                                outputBuf.append(line).append('\n')
                                liveLog(stageDir, "  | $line")
                            }
                        } catch (_: Exception) {}
                    }
                    readerThread.start()
                    val finished = process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS)
                    readerThread.join(3000)
                    if (!finished) {
                        process.destroyForcibly()
                        val msg = "Error: command timed out after ${COMMAND_TIMEOUT_SEC}s"
                        liveLog(stageDir, "  ✗ $msg")
                        return@ToolDefinition msg
                    }
                    val exitCode = process.exitValue()
                    liveLog(stageDir, "  Exit code: $exitCode")
                    val output = outputBuf.toString().let { out ->
                        if (out.length > MAX_OUTPUT_CHARS)
                            out.take(MAX_OUTPUT_CHARS) + "\n... (truncated, ${out.length} total chars)"
                        else out
                    }
                    "Exit code: $exitCode\n$output"
                } catch (e: Exception) {
                    val err = "Error running command: ${e.message}"
                    liveLog(stageDir, "  ✗ $err")
                    err
                }
            }
        ),

        ToolDefinition(
            name = "list_files",
            description = "List files in the workspace or a subdirectory.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Relative path to list (default: workspace root)")
                ),
                "required" to emptyList<String>()
            ),
            execute = { args ->
                val path = args["path"]?.toString() ?: "."
                liveLog(stageDir, "→ list_files: $path")
                try {
                    val target = workspaceDir.resolve(path).canonicalFile
                    if (!target.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
                        "Error: path '$path' escapes the workspace directory"
                    } else if (!target.exists()) {
                        "Directory not found: $path"
                    } else {
                        val files = target.walkTopDown()
                            .filter { it.isFile }
                            .map { it.relativeTo(workspaceDir).path }
                            .sorted()
                            .toList()
                        if (files.isEmpty()) "(empty workspace)"
                        else files.joinToString("\n")
                    }
                } catch (e: Exception) {
                    "Error listing files: ${e.message}"
                }
            }
        )
    )
}
