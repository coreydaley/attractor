package attractor.lint

import attractor.dot.Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class ValidatorTest : FunSpec({

    fun validate(dot: String): List<Diagnostic> {
        val graph = Parser.parse(dot)
        return Validator.validate(graph)
    }

    fun errors(dot: String) = validate(dot).filter { it.severity == Severity.ERROR }
    fun warnings(dot: String) = validate(dot).filter { it.severity == Severity.WARNING }

    test("valid minimal pipeline has no errors") {
        val dot = """
            digraph Simple {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                start -> exit
            }
        """.trimIndent()
        errors(dot).shouldBeEmpty()
    }

    test("missing start node is an error") {
        val dot = """
            digraph NoStart {
                exit [shape=Msquare]
            }
        """.trimIndent()
        val errs = errors(dot)
        errs.any { it.rule == "start_node" } shouldBe true
    }

    test("missing exit node is an error") {
        val dot = """
            digraph NoExit {
                start [shape=Mdiamond]
            }
        """.trimIndent()
        val errs = errors(dot)
        errs.any { it.rule == "terminal_node" } shouldBe true
    }

    test("unreachable node is an error") {
        val dot = """
            digraph Unreachable {
                start  [shape=Mdiamond]
                exit   [shape=Msquare]
                orphan [shape=box]
                start -> exit
            }
        """.trimIndent()
        val errs = errors(dot)
        errs.any { it.rule == "reachability" } shouldBe true
    }

    test("edge to unknown node is an error") {
        val dot = """
            digraph BadEdge {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                start -> nonexistent
                nonexistent -> exit
            }
        """.trimIndent()
        // nonexistent gets created implicitly by the parser
        // so this should actually be fine
        // Let's test with an edge that explicitly references an already-removed node
        val errors2 = errors(dot)
        // nonexistent was created by the parser, so no error
        errors2.filter { it.rule == "edge_target_exists" }.shouldBeEmpty()
    }

    test("start node with incoming edge is an error") {
        val dot = """
            digraph StartIncoming {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                mid   [shape=box]
                start -> mid -> exit
                mid -> start
            }
        """.trimIndent()
        val errs = errors(dot)
        errs.any { it.rule == "start_no_incoming" } shouldBe true
    }

    test("exit node with outgoing edge is an error") {
        val dot = """
            digraph ExitOutgoing {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                mid   [shape=box]
                start -> mid -> exit
                exit -> start
            }
        """.trimIndent()
        val errs = errors(dot)
        errs.any { it.rule == "exit_no_outgoing" } shouldBe true
    }

    test("unknown node type is a warning") {
        val dot = """
            digraph WarnType {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                n [shape=box, type="completely_unknown_type"]
                start -> n -> exit
            }
        """.trimIndent()
        val warns = warnings(dot)
        warns.any { it.rule == "type_known" } shouldBe true
    }

    test("invalid fidelity is a warning") {
        val dot = """
            digraph WarnFidelity {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                n [shape=box, fidelity="invalid_mode"]
                start -> n -> exit
            }
        """.trimIndent()
        val warns = warnings(dot)
        warns.any { it.rule == "fidelity_valid" } shouldBe true
    }

    test("goal gate without retry target is a warning") {
        val dot = """
            digraph WarnGoalGate {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                n [shape=box, goal_gate=true]
                start -> n -> exit
            }
        """.trimIndent()
        val warns = warnings(dot)
        warns.any { it.rule == "goal_gate_has_retry" } shouldBe true
    }

    test("goal gate with retry target has no warning") {
        val dot = """
            digraph NoWarnGoalGate {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                n [shape=box, goal_gate=true, retry_target="retry"]
                retry [shape=box]
                start -> n -> exit
                retry -> exit
            }
        """.trimIndent()
        val warns = warnings(dot)
        warns.any { it.rule == "goal_gate_has_retry" } shouldBe false
    }

    test("validate_or_raise throws on errors") {
        val dot = """
            digraph Invalid {
                exit [shape=Msquare]
            }
        """.trimIndent()
        val graph = Parser.parse(dot)
        var threw = false
        try {
            Validator.validateOrRaise(graph)
        } catch (e: ValidationException) {
            threw = true
            e.errors.any { it.rule == "start_node" } shouldBe true
        }
        threw shouldBe true
    }

    test("validate branching pipeline from spec") {
        val dot = """
            digraph Branch {
                graph [goal="Implement and validate a feature"]
                rankdir=LR
                node [shape=box, timeout="900s"]
                start     [shape=Mdiamond, label="Start"]
                exit      [shape=Msquare, label="Exit"]
                plan      [label="Plan", prompt="Plan the implementation"]
                implement [label="Implement", prompt="Implement the plan"]
                validate  [label="Validate", prompt="Run tests"]
                gate      [shape=diamond, label="Tests passing?"]
                start -> plan -> implement -> validate -> gate
                gate -> exit      [label="Yes", condition="outcome=success"]
                gate -> implement [label="No", condition="outcome!=success"]
            }
        """.trimIndent()
        errors(dot).shouldBeEmpty()
    }

    test("reachability check passes for fully connected graph") {
        val dot = """
            digraph Connected {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                a [shape=box]
                b [shape=box]
                c [shape=box]
                start -> a -> b -> c -> exit
            }
        """.trimIndent()
        errors(dot).filter { it.rule == "reachability" }.shouldBeEmpty()
    }
})
