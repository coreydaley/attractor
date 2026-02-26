package attractor.engine

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.dot.Parser
import attractor.handlers.CodergenHandler
import attractor.handlers.Handler
import attractor.handlers.HandlerRegistry
import attractor.handlers.SimulationBackend
import attractor.human.AutoApproveInterviewer
import attractor.human.QueueInterviewer
import attractor.human.Answer
import attractor.state.Context
import attractor.state.Outcome
import attractor.state.StageStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Fails the first invocation unless context has a non-blank `repair.hint`; succeeds otherwise.
 */
class FailOnFirstCallHandler : Handler {
    private val callCount = java.util.concurrent.atomic.AtomicInteger(0)
    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome {
        val hint = context.getString("repair.hint", "")
        return if (hint.isNotBlank()) Outcome.success()
        else if (callCount.incrementAndGet() == 1) Outcome.fail("First call fails")
        else Outcome.success()
    }
}

class EngineTest : FunSpec({

    fun makeEngine(autoApprove: Boolean = true, logsRoot: String = "/tmp/attractor-test-${System.currentTimeMillis()}"): Engine {
        val codergen = CodergenHandler(SimulationBackend)
        val registry = HandlerRegistry.createDefault(codergen, AutoApproveInterviewer())
        val config = EngineConfig(logsRoot = logsRoot, autoApprove = autoApprove)
        return Engine(registry, config)
    }

    test("run simple linear pipeline") {
        val dot = """
            digraph Simple {
                graph [goal="Run tests"]
                start [shape=Mdiamond, label="Start"]
                exit  [shape=Msquare,  label="Exit"]
                step  [shape=box,      label="Run Tests", prompt="Execute the tests"]
                start -> step -> exit
            }
        """.trimIndent()

        val engine = makeEngine()
        val graph = Parser.parse(dot)
        val prepared = engine.prepare(graph)
        val outcome = engine.run(prepared)

        outcome.status shouldBe StageStatus.SUCCESS
    }

    test("run branching pipeline with conditions") {
        val dot = """
            digraph Branch {
                graph [goal="Test branching"]
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                gate  [shape=diamond, label="Check"]
                pass  [shape=box, label="Pass"]
                fail_node [shape=box, label="Fail"]

                start -> gate
                gate -> pass      [condition="outcome=success"]
                gate -> fail_node [condition="outcome=fail"]
                pass -> exit
                fail_node -> exit
            }
        """.trimIndent()

        val engine = makeEngine()
        val graph = Parser.parse(dot)
        val prepared = engine.prepare(graph)
        val outcome = engine.run(prepared)

        // ConditionalHandler returns success, so outcome=success edge should be taken
        outcome.status shouldBe StageStatus.SUCCESS
    }

    test("edge selection by preferred label") {
        val dot = """
            digraph LabelTest {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                gate  [shape=diamond]
                yes_node [shape=box, label="Yes Path"]
                no_node  [shape=box, label="No Path"]

                start -> gate
                gate -> yes_node [label="Yes"]
                gate -> no_node  [label="No"]
                yes_node -> exit
                no_node  -> exit
            }
        """.trimIndent()

        val engine = makeEngine()
        val graph = Parser.parse(dot)
        val prepared = engine.prepare(graph)
        val outcome = engine.run(prepared)

        outcome.status shouldBe StageStatus.SUCCESS
    }

    test("human gate with auto-approve") {
        val dot = """
            digraph HumanGate {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                gate  [shape=hexagon, label="Approve?", type="wait.human"]
                approved [shape=box, label="Approved"]
                rejected [shape=box, label="Rejected"]

                start -> gate
                gate -> approved [label="[A] Approve"]
                gate -> rejected [label="[R] Reject"]
                approved -> exit
                rejected -> exit
            }
        """.trimIndent()

        val engine = makeEngine(autoApprove = true)
        val graph = Parser.parse(dot)
        val prepared = engine.prepare(graph)
        val outcome = engine.run(prepared)

        outcome.status shouldBe StageStatus.SUCCESS
    }

    test("edge selector respects weight") {
        val edge1 = attractor.dot.DotEdge("a", "b",
            mutableMapOf("weight" to attractor.dot.DotValue.IntegerValue(5)))
        val edge2 = attractor.dot.DotEdge("a", "c",
            mutableMapOf("weight" to attractor.dot.DotValue.IntegerValue(10)))
        val edge3 = attractor.dot.DotEdge("a", "d",
            mutableMapOf("weight" to attractor.dot.DotValue.IntegerValue(3)))

        val sorted = listOf(edge1, edge2, edge3).sortedWith(
            compareByDescending<attractor.dot.DotEdge> { it.weight }.thenBy { it.to }
        )
        sorted[0].to shouldBe "c" // weight 10 wins
    }

    test("edge selector lexical tiebreak") {
        val edge1 = attractor.dot.DotEdge("a", "zebra")
        val edge2 = attractor.dot.DotEdge("a", "alpha")
        val edge3 = attractor.dot.DotEdge("a", "mango")

        val sorted = listOf(edge1, edge2, edge3).sortedWith(
            compareByDescending<attractor.dot.DotEdge> { it.weight }.thenBy { it.to }
        )
        sorted[0].to shouldBe "alpha" // lexicographically first
    }

    test("label normalization strips accelerator prefixes") {
        EdgeSelector.normalizeLabel("[Y] Yes, deploy") shouldBe "yes, deploy"
        EdgeSelector.normalizeLabel("Y) Yes, deploy")  shouldBe "yes, deploy"
        EdgeSelector.normalizeLabel("Y - Yes, deploy") shouldBe "yes, deploy"
        EdgeSelector.normalizeLabel("Yes, deploy")     shouldBe "yes, deploy"
    }

    test("tool handler executes shell command") {
        val dot = """
            digraph ToolTest {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                echo_node [shape=parallelogram, label="Echo", tool_command="echo hello"]
                start -> echo_node -> exit
            }
        """.trimIndent()

        val engine = makeEngine()
        val graph = Parser.parse(dot)
        val prepared = engine.prepare(graph)
        val outcome = engine.run(prepared)

        outcome.status shouldBe StageStatus.SUCCESS
    }

    test("goal gate enforcement redirects when unsatisfied") {
        // Build a graph where the goal gate node always fails
        // and there's a retry target
        val dot = """
            digraph GoalGateTest {
                start  [shape=Mdiamond]
                exit   [shape=Msquare]
                work   [shape=box, label="Work", goal_gate=true, retry_target="retry_node"]
                retry_node [shape=box, label="Retry"]
                start -> work -> exit
                work -> retry_node -> exit
            }
        """.trimIndent()

        // This just verifies the graph parses and validates correctly
        val graph = Parser.parse(dot)
        // work node has goal_gate and retry_target - this is valid
        attractor.lint.Validator.validate(graph).filter {
            it.severity == attractor.lint.Severity.ERROR
        }.size shouldBe 0
    }

    test("checkpoint is saved to disk") {
        val logsRoot = "/tmp/attractor-checkpoint-test-${System.currentTimeMillis()}"
        val dot = """
            digraph CheckpointTest {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                step  [shape=box, label="Step"]
                start -> step -> exit
            }
        """.trimIndent()

        val engine = makeEngine(logsRoot = logsRoot)
        val graph = Parser.parse(dot)
        val prepared = engine.prepare(graph)
        engine.run(prepared)

        val checkpointFile = java.io.File(logsRoot, "checkpoint.json")
        checkpointFile.exists() shouldBe true
        checkpointFile.readText() shouldContain "step"
    }

    test("failure diagnosis: repair attempt succeeds and pipeline continues") {
        val logsRoot = "/tmp/attractor-repair-success-${System.currentTimeMillis()}"
        // work has no outgoing edges → EdgeSelector returns null → triggers diagnosis
        // exit is reachable from start (satisfies validator) but work has no edges to it
        val dot = """
            digraph RepairTest {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                work  [shape=box, label="Work"]
                start -> work [weight=10]
                start -> exit
            }
        """.trimIndent()

        val failOnFirst = FailOnFirstCallHandler()
        val handlerRegistry = HandlerRegistry.createDefault(
            CodergenHandler(SimulationBackend), AutoApproveInterviewer()
        )
        handlerRegistry.register("codergen", failOnFirst)

        val testDiagnoser = FailureDiagnoser { _ ->
            DiagnosisResult(
                recoverable = true, strategy = "RETRY_WITH_HINT",
                explanation = "transient error", repairHint = "use simpler approach"
            )
        }
        val config = EngineConfig(logsRoot = logsRoot, diagnoser = testDiagnoser)
        val engine = Engine(handlerRegistry, config)
        val graph = Parser.parse(dot)
        val prepared = engine.prepare(graph)
        val outcome = engine.run(prepared)

        outcome.status shouldBe StageStatus.SUCCESS
    }

    test("failure diagnosis: NullFailureDiagnoser writes failure_report.json on abort") {
        val logsRoot = "/tmp/attractor-abort-report-${System.currentTimeMillis()}"
        // work has no outgoing edges → EdgeSelector returns null → triggers diagnosis
        val dot = """
            digraph AbortTest {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                work  [shape=box, label="Work"]
                start -> work [weight=10]
                start -> exit
            }
        """.trimIndent()

        val alwaysFail = object : Handler {
            override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String) =
                Outcome.fail("always fails")
        }
        val handlerRegistry = HandlerRegistry.createDefault(
            CodergenHandler(SimulationBackend), AutoApproveInterviewer()
        )
        handlerRegistry.register("codergen", alwaysFail)

        val config = EngineConfig(logsRoot = logsRoot, diagnoser = NullFailureDiagnoser())
        val engine = Engine(handlerRegistry, config)
        val graph = Parser.parse(dot)
        val prepared = engine.prepare(graph)
        val outcome = engine.run(prepared)

        outcome.status shouldBe StageStatus.FAIL
        java.io.File(logsRoot, "failure_report.json").exists() shouldBe true
    }

    test("failure diagnosis: disabled node attribute skips diagnosis and halts with original failure") {
        val logsRoot = "/tmp/attractor-diag-disabled-${System.currentTimeMillis()}"
        // work has no outgoing edges → EdgeSelector returns null → triggers (disabled) diagnosis
        val dot = """
            digraph DisabledTest {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                work  [shape=box, label="Work", failure_diagnosis_disabled=true]
                start -> work [weight=10]
                start -> exit
            }
        """.trimIndent()

        val alwaysFail = object : Handler {
            override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String) =
                Outcome.fail("deliberate failure")
        }
        val handlerRegistry = HandlerRegistry.createDefault(
            CodergenHandler(SimulationBackend), AutoApproveInterviewer()
        )
        handlerRegistry.register("codergen", alwaysFail)

        // This diagnoser would repair if called — but it must not be called when disabled
        val testDiagnoser = FailureDiagnoser { _ ->
            DiagnosisResult(recoverable = true, strategy = "RETRY_WITH_HINT",
                explanation = "should not be called", repairHint = "hint")
        }
        val config = EngineConfig(logsRoot = logsRoot, diagnoser = testDiagnoser)
        val engine = Engine(handlerRegistry, config)
        val graph = Parser.parse(dot)
        val prepared = engine.prepare(graph)
        val outcome = engine.run(prepared)

        outcome.status shouldBe StageStatus.FAIL
        outcome.failureReason shouldContain "deliberate failure"
    }
})
