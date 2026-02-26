package attractor.web

import attractor.db.RunStore
import attractor.dot.Parser
import attractor.engine.Engine
import attractor.engine.EngineConfig
import attractor.engine.FailureDiagnoser
import attractor.engine.LlmFailureDiagnoser
import attractor.engine.NullFailureDiagnoser
import attractor.events.PipelineEvent
import attractor.handlers.CodergenHandler
import attractor.state.Checkpoint
import attractor.handlers.HandlerRegistry
import attractor.handlers.LlmCodergenBackend
import attractor.handlers.SimulationBackend
import attractor.human.AutoApproveInterviewer
import attractor.llm.Client
import attractor.transform.StylesheetApplicationTransform
import attractor.transform.VariableExpansionTransform
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class RunOptions(
    val simulate: Boolean = false,
    val autoApprove: Boolean = true
)

object PipelineRunner {
    private val executor = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(4)
    )
    private val counter = AtomicLong(0)

    fun submit(
        dotSource: String,
        fileName: String,
        options: RunOptions,
        registry: PipelineRegistry,
        store: RunStore,
        originalPrompt: String = "",
        familyId: String = "",
        displayNameOverride: String = "",
        onUpdate: () -> Unit
    ): String {
        val id = "run-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        val displayName = displayNameOverride.ifBlank { NameGenerator.generate() }
        // Self-bootstrap: if no familyId provided, this run starts its own family
        val effectiveFamilyId = familyId.ifBlank { id }
        val state = registry.register(id, fileName, dotSource, options, originalPrompt, displayName, effectiveFamilyId)

        val future = executor.submit {
            runPipeline(id, dotSource, options, state, registry, store, onUpdate)
        }
        registry.registerFuture(id, future)

        return id
    }

    fun resubmit(
        id: String,
        registry: PipelineRegistry,
        store: RunStore,
        onUpdate: () -> Unit
    ) {
        val entry = registry.get(id) ?: return
        entry.state.reset()
        store.updateStatus(id, "running")
        onUpdate()
        val future = executor.submit {
            runPipeline(id, entry.dotSource, entry.options, entry.state, registry, store, onUpdate)
        }
        registry.registerFuture(id, future)
    }

    fun resumePipeline(
        id: String,
        registry: PipelineRegistry,
        store: RunStore,
        onUpdate: () -> Unit
    ) {
        val entry = registry.get(id) ?: return
        if (entry.state.status.get() != "paused") return
        entry.state.pauseToken.set(false)
        entry.state.status.set("running")
        store.updateStatus(id, "running")
        onUpdate()
        val future = executor.submit {
            // resume = true: engine will load checkpoint and continue from last saved node
            // NOTE: Checkpoint.create() records currentNode = <just-completed-node-id>,
            // so the engine re-runs that node on resume. This is intentional existing behaviour.
            runPipeline(id, entry.dotSource, entry.options, entry.state, registry, store, onUpdate, resume = true)
        }
        registry.registerFuture(id, future)
    }

    private fun runPipeline(
        id: String,
        dotSource: String,
        options: RunOptions,
        state: PipelineState,
        registry: PipelineRegistry,
        store: RunStore,
        onUpdate: () -> Unit,
        resume: Boolean = false
    ) {
        try {
            val graph = Parser.parse(dotSource)

            val hasKey = listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY")
                .any { !System.getenv(it).isNullOrBlank() }

            val backend = if (options.simulate) {
                SimulationBackend
            } else {
                if (hasKey) LlmCodergenBackend(Client.fromEnv()) else SimulationBackend
            }

            val diagnoser: FailureDiagnoser = when {
                options.simulate -> NullFailureDiagnoser("simulation mode: diagnosis skipped")
                hasKey           -> LlmFailureDiagnoser(Client.fromEnv())
                else             -> NullFailureDiagnoser()
            }

            val interviewer = AutoApproveInterviewer()
            val codergenHandler = CodergenHandler(backend)
            val handlerRegistry = HandlerRegistry.createDefault(codergenHandler, interviewer)

            val logsRoot = registry.get(id)?.logsRoot?.takeIf { it.isNotBlank() }
                ?: "logs/${graph.id}-$id"
            registry.setLogsRoot(id, logsRoot)
            registry.get(id)?.state?.logsRoot = logsRoot

            // On resume: snapshot completed stages (excluding checkpoint.currentNode, which the
            // engine will re-run) so we can restore them after PipelineStarted clears the list.
            val stagesToRestore: List<StageRecord> = if (resume) {
                val ckpt = Checkpoint.load(logsRoot)
                if (ckpt != null) {
                    state.stages.filter { it.status == "completed" && it.nodeId != ckpt.currentNode }
                } else emptyList()
            } else emptyList()

            val config = EngineConfig(
                logsRoot = logsRoot,
                autoApprove = options.autoApprove,
                resume = resume,
                transforms = listOf(VariableExpansionTransform(), StylesheetApplicationTransform()),
                cancelCheck = { state.cancelToken.get() },
                pauseCheck  = { state.pauseToken.get() },
                diagnoser = diagnoser
            )

            val engine = Engine(handlerRegistry, config)
            engine.subscribe { event ->
                state.update(event)
                if (event is PipelineEvent.PipelineStarted) {
                    val name = registry.get(id)?.displayName
                    if (!name.isNullOrBlank()) state.pipelineName.set(name)
                }
                // After PipelineStarted clears the stage list, restore completed stages from
                // before the pause. The checkpoint's currentNode is excluded because the engine
                // re-runs it and will emit its own StageStarted/StageCompleted events.
                if (resume && event is PipelineEvent.PipelineStarted && stagesToRestore.isNotEmpty()) {
                    state.stages.addAll(0, stagesToRestore)
                }
                // Persist terminal statuses and full log to the database
                when (event) {
                    is PipelineEvent.PipelineCompleted -> { store.updateStatus(id, "completed"); store.updateLog(id, state.recentLogs.joinToString("\n")); store.updateFinishedAt(id, state.finishedAt.get()) }
                    is PipelineEvent.PipelineFailed    -> {
                        store.updateStatus(id, "failed")
                        store.updateLog(id, state.recentLogs.joinToString("\n"))
                        store.updateFinishedAt(id, state.finishedAt.get())
                        val lr = registry.get(id)?.logsRoot ?: ""
                        if (lr.isNotBlank() && java.io.File(lr, "failure_report.json").exists()) {
                            state.hasFailureReport.set(true)
                        }
                    }
                    is PipelineEvent.PipelineCancelled -> { store.updateStatus(id, "cancelled"); store.updateLog(id, state.recentLogs.joinToString("\n")); store.updateFinishedAt(id, state.finishedAt.get()) }
                    is PipelineEvent.PipelinePaused    -> { store.updateStatus(id, "paused");    store.updateLog(id, state.recentLogs.joinToString("\n")); store.updateFinishedAt(id, state.finishedAt.get()) }
                    else -> {}
                }
                when (event) {
                    is PipelineEvent.CheckpointSaved -> { /* state updated; no broadcast needed */ }
                    else -> onUpdate()
                }
            }

            val preparedGraph = engine.prepare(graph)
            engine.run(preparedGraph)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            state.update(attractor.events.PipelineEvent.PipelineCancelled(0))
            store.updateStatus(id, "cancelled")
            store.updateLog(id, state.recentLogs.joinToString("\n"))
            store.updateFinishedAt(id, state.finishedAt.get())
            onUpdate()
        } catch (e: Exception) {
            state.update(PipelineEvent.PipelineFailed(e.message ?: "Unknown error", 0))
            store.updateStatus(id, "failed")
            store.updateLog(id, state.recentLogs.joinToString("\n"))
            store.updateFinishedAt(id, state.finishedAt.get())
            onUpdate()
        }
    }
}
