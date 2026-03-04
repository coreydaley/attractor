package attractor.web

import attractor.db.RunStore
import attractor.dot.Parser
import attractor.engine.Engine
import attractor.engine.EngineConfig
import attractor.engine.FailureDiagnoser
import attractor.engine.LlmFailureDiagnoser
import attractor.engine.NullFailureDiagnoser
import attractor.events.ProjectEvent
import attractor.handlers.CodergenHandler
import attractor.state.Checkpoint
import attractor.handlers.HandlerRegistry
import attractor.handlers.LlmCodergenBackend
import attractor.handlers.SimulationBackend
import attractor.human.AutoApproveInterviewer
import attractor.llm.ClientProvider
import attractor.transform.StylesheetApplicationTransform
import attractor.transform.VariableExpansionTransform
import attractor.workspace.WorkspaceGit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class RunOptions(
    val simulate: Boolean = false,
    val autoApprove: Boolean = true
)

object ProjectRunner {
    private val executor = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(4)
    )
    private val counter = AtomicLong(0)

    fun submit(
        dotSource: String,
        fileName: String,
        options: RunOptions,
        registry: ProjectRegistry,
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
            runProject(id, dotSource, options, state, registry, store, onUpdate)
        }
        registry.registerFuture(id, future)

        return id
    }

    fun resubmit(
        id: String,
        registry: ProjectRegistry,
        store: RunStore,
        onUpdate: () -> Unit
    ) {
        val entry = registry.get(id) ?: return
        entry.state.reset()
        store.updateStatus(id, "running")
        onUpdate()
        val future = executor.submit {
            runProject(id, entry.dotSource, entry.options, entry.state, registry, store, onUpdate)
        }
        registry.registerFuture(id, future)
    }

    fun resumeProject(
        id: String,
        registry: ProjectRegistry,
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
            runProject(id, entry.dotSource, entry.options, entry.state, registry, store, onUpdate, resume = true)
        }
        registry.registerFuture(id, future)
    }

    private fun runProject(
        id: String,
        dotSource: String,
        options: RunOptions,
        state: ProjectState,
        registry: ProjectRegistry,
        store: RunStore,
        onUpdate: () -> Unit,
        resume: Boolean = false
    ) {
        try {
            val graph = Parser.parse(dotSource)

            val client = try { ClientProvider.fromStore(store) } catch (_: Exception) { null }

            val backend = if (options.simulate) {
                SimulationBackend
            } else {
                if (client != null) LlmCodergenBackend(client) else SimulationBackend
            }

            val diagnoser: FailureDiagnoser = when {
                options.simulate -> NullFailureDiagnoser("simulation mode: diagnosis skipped")
                client != null   -> LlmFailureDiagnoser(client)
                else             -> NullFailureDiagnoser()
            }

            val interviewer = AutoApproveInterviewer()
            val codergenHandler = CodergenHandler(backend)
            val handlerRegistry = HandlerRegistry.createDefault(codergenHandler, interviewer)

            val rawName = registry.get(id)?.displayName?.takeIf { it.isNotBlank() } ?: graph.id
            val safeName = rawName.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').ifBlank { id }
            val projectsBase = System.getProperty("attractor.projects.dir", "projects")
            val logsRoot = registry.get(id)?.logsRoot?.takeIf { it.isNotBlank() }
                ?: "$projectsBase/$safeName"
            registry.setLogsRoot(id, logsRoot)
            registry.get(id)?.state?.logsRoot = logsRoot
            val workspaceDir = "$logsRoot/workspace"
            WorkspaceGit.init(workspaceDir)

            // On resume: snapshot completed stages (excluding checkpoint.currentNode, which the
            // engine will re-run) so we can restore them after ProjectStarted clears the list.
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
                if (event is ProjectEvent.ProjectStarted) {
                    val name = registry.get(id)?.displayName
                    if (!name.isNullOrBlank()) state.projectName.set(name)
                }
                // After ProjectStarted clears the stage list, restore completed stages from
                // before the pause. The checkpoint's currentNode is excluded because the engine
                // re-runs it and will emit its own StageStarted/StageCompleted events.
                if (resume && event is ProjectEvent.ProjectStarted && stagesToRestore.isNotEmpty()) {
                    state.stages.addAll(0, stagesToRestore)
                }
                // Persist terminal statuses and full log to the database
                val prompt = registry.get(id)?.originalPrompt ?: ""
                when (event) {
                    is ProjectEvent.ProjectCompleted -> {
                        store.updateStatus(id, "completed"); store.updateLog(id, state.recentLogs.joinToString("\n")); store.updateFinishedAt(id, state.finishedAt.get())
                        WorkspaceGit.commitIfChanged(workspaceDir, "Run $id completed: ${state.stages.count { it.status == "completed" }} stages", prompt)
                    }
                    is ProjectEvent.ProjectFailed    -> {
                        store.updateStatus(id, "failed")
                        store.updateLog(id, state.recentLogs.joinToString("\n"))
                        store.updateFinishedAt(id, state.finishedAt.get())
                        val lr = registry.get(id)?.logsRoot ?: ""
                        if (lr.isNotBlank() && java.io.File(lr, "failure_report.json").exists()) {
                            state.hasFailureReport.set(true)
                        }
                        WorkspaceGit.commitIfChanged(workspaceDir, "Run $id failed: ${state.stages.count { it.status == "completed" }} stages completed", prompt)
                    }
                    is ProjectEvent.ProjectCancelled -> {
                        store.updateStatus(id, "cancelled"); store.updateLog(id, state.recentLogs.joinToString("\n")); store.updateFinishedAt(id, state.finishedAt.get())
                        WorkspaceGit.commitIfChanged(workspaceDir, "Run $id cancelled", prompt)
                    }
                    is ProjectEvent.ProjectPaused    -> {
                        store.updateStatus(id, "paused");    store.updateLog(id, state.recentLogs.joinToString("\n")); store.updateFinishedAt(id, state.finishedAt.get())
                        WorkspaceGit.commitIfChanged(workspaceDir, "Run $id paused: checkpoint saved", prompt)
                    }
                    else -> {}
                }
                when (event) {
                    is ProjectEvent.CheckpointSaved -> { /* state updated; no broadcast needed */ }
                    else -> onUpdate()
                }
            }

            val preparedGraph = engine.prepare(graph)
            engine.run(preparedGraph)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            state.update(attractor.events.ProjectEvent.ProjectCancelled(0))
            store.updateStatus(id, "cancelled")
            store.updateLog(id, state.recentLogs.joinToString("\n"))
            store.updateFinishedAt(id, state.finishedAt.get())
            val cancelLogsRoot = registry.get(id)?.logsRoot ?: ""
            val cancelPrompt = registry.get(id)?.originalPrompt ?: ""
            if (cancelLogsRoot.isNotBlank()) WorkspaceGit.commitIfChanged("$cancelLogsRoot/workspace", "Run $id cancelled", cancelPrompt)
            onUpdate()
        } catch (e: Exception) {
            state.update(ProjectEvent.ProjectFailed(e.message ?: "Unknown error", 0))
            store.updateStatus(id, "failed")
            store.updateLog(id, state.recentLogs.joinToString("\n"))
            store.updateFinishedAt(id, state.finishedAt.get())
            val failLogsRoot = registry.get(id)?.logsRoot ?: ""
            val failPrompt = registry.get(id)?.originalPrompt ?: ""
            if (failLogsRoot.isNotBlank()) WorkspaceGit.commitIfChanged("$failLogsRoot/workspace", "Run $id failed: ${state.stages.count { it.status == "completed" }} stages completed", failPrompt)
            onUpdate()
        }
    }
}
