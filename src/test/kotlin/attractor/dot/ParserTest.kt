package attractor.dot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ParserTest : FunSpec({

    test("parse minimal digraph") {
        val dot = """
            digraph Simple {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                start -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.id shouldBe "Simple"
        graph.nodes.size shouldBe 2
        graph.edges shouldHaveSize 1
        graph.nodes["start"].shouldNotBeNull()
        graph.nodes["exit"].shouldNotBeNull()
        graph.edges[0].from shouldBe "start"
        graph.edges[0].to shouldBe "exit"
    }

    test("parse graph attributes") {
        val dot = """
            digraph Pipeline {
                graph [goal="Run tests", label="Test Pipeline"]
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                start -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.goal shouldBe "Run tests"
        graph.label shouldBe "Test Pipeline"
    }

    test("parse node attributes") {
        val dot = """
            digraph Test {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                plan [
                    shape=box,
                    label="Plan the work",
                    prompt="Create a plan for the work",
                    max_retries=3,
                    goal_gate=true
                ]
                start -> plan -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        val plan = graph.nodes["plan"].shouldNotBeNull()
        plan.shape shouldBe "box"
        plan.label shouldBe "Plan the work"
        plan.prompt shouldBe "Create a plan for the work"
        plan.maxRetries shouldBe 3
        plan.goalGate shouldBe true
    }

    test("parse edge attributes") {
        val dot = """
            digraph Test {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                gate  [shape=diamond]
                start -> gate
                gate -> exit   [label="Yes", condition="outcome=success", weight=10]
                gate -> start  [label="No",  condition="outcome=fail",    weight=0]
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.edges shouldHaveSize 3
        val successEdge = graph.edges.find { it.from == "gate" && it.to == "exit" }.shouldNotBeNull()
        successEdge.label shouldBe "Yes"
        successEdge.condition shouldBe "outcome=success"
        successEdge.weight shouldBe 10
    }

    test("parse chained edges") {
        val dot = """
            digraph Test {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                a [shape=box]
                b [shape=box]
                start -> a -> b -> exit [label="next"]
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        // A->B->C->D produces 3 edges
        graph.edges shouldHaveSize 3
        graph.edges[0].from shouldBe "start"
        graph.edges[0].to shouldBe "a"
        graph.edges[0].label shouldBe "next"
        graph.edges[1].from shouldBe "a"
        graph.edges[1].to shouldBe "b"
        graph.edges[2].from shouldBe "b"
        graph.edges[2].to shouldBe "exit"
    }

    test("parse node defaults") {
        val dot = """
            digraph Test {
                node [shape=box, timeout="900s"]
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                plan [label="Plan"]
                implement [label="Implement"]
                start -> plan -> implement -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        // Nodes declared after node defaults should inherit them
        val plan = graph.nodes["plan"].shouldNotBeNull()
        // plan was declared after node defaults, so shape=box is default
        // but shape=box is also the node's explicit shape default
        plan.timeoutMillis shouldBe 900_000L
    }

    test("parse boolean values") {
        val dot = """
            digraph Test {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                n [goal_gate=true, auto_status=false]
                start -> n -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        val node = graph.nodes["n"].shouldNotBeNull()
        node.goalGate shouldBe true
        node.autoStatus shouldBe false
    }

    test("parse duration values") {
        val dot = """
            digraph Test {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                n [timeout="900s"]
                m [timeout="15m"]
                o [timeout="2h"]
                p [timeout="250ms"]
                start -> n -> m -> o -> p -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.nodes["n"]!!.timeoutMillis shouldBe 900_000L
        graph.nodes["m"]!!.timeoutMillis shouldBe 900_000L
        graph.nodes["o"]!!.timeoutMillis shouldBe 7_200_000L
        graph.nodes["p"]!!.timeoutMillis shouldBe 250L
    }

    test("strip line comments") {
        val dot = """
            // This is a comment
            digraph Test {
                start [shape=Mdiamond] // inline comment
                exit  [shape=Msquare]
                start -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.nodes.size shouldBe 2
    }

    test("strip block comments") {
        val dot = """
            /* Block comment */
            digraph Test {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                /* Another block
                   comment */
                start -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.nodes.size shouldBe 2
    }

    test("parse subgraph") {
        val dot = """
            digraph Test {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                subgraph cluster_loop {
                    label = "Loop A"
                    node [timeout="900s"]
                    plan [label="Plan"]
                    impl [label="Implement"]
                }
                start -> plan -> impl -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.nodes["plan"].shouldNotBeNull()
        graph.nodes["impl"].shouldNotBeNull()
        graph.edges shouldHaveSize 3
    }

    test("parse model stylesheet") {
        val dot = """
            digraph Pipeline {
                graph [
                    model_stylesheet="* { llm_model: claude-sonnet-4-5; }"
                ]
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                start -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.modelStylesheet shouldContain "claude-sonnet-4-5"
    }

    test("parse integer and float values") {
        val dot = """
            digraph Test {
                graph [default_max_retry=50]
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                n [max_retries=3]
                start -> n [weight=5]
                n -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.defaultMaxRetry shouldBe 50
        graph.nodes["n"]!!.maxRetries shouldBe 3
        graph.edges[0].weight shouldBe 5
    }

    test("parse start and exit node detection") {
        val dot = """
            digraph Test {
                start [shape=Mdiamond]
                exit  [shape=Msquare]
                start -> exit
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.startNode().shouldNotBeNull()
        graph.exitNode().shouldNotBeNull()
        graph.startNode()!!.id shouldBe "start"
        graph.exitNode()!!.id shouldBe "exit"
    }

    test("parse human gate example from spec") {
        val dot = """
            digraph Review {
                rankdir=LR
                start [shape=Mdiamond, label="Start"]
                exit  [shape=Msquare, label="Exit"]
                review_gate [
                    shape=hexagon,
                    label="Review Changes",
                    type="wait.human"
                ]
                ship_it [shape=box, label="Ship It"]
                fixes   [shape=box, label="Fixes"]
                start -> review_gate
                review_gate -> ship_it [label="[A] Approve"]
                review_gate -> fixes   [label="[F] Fix"]
                ship_it -> exit
                fixes -> review_gate
            }
        """.trimIndent()

        val graph = Parser.parse(dot)
        graph.nodes.size shouldBe 5
        val gate = graph.nodes["review_gate"].shouldNotBeNull()
        gate.shape shouldBe "hexagon"
        gate.type shouldBe "wait.human"
        graph.outgoingEdges("review_gate") shouldHaveSize 2
    }
})
