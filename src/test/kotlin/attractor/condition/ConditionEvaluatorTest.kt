package attractor.condition

import attractor.state.Context
import attractor.state.Outcome
import attractor.state.StageStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConditionEvaluatorTest : FunSpec({

    fun ctx(vararg pairs: Pair<String, String>): Context {
        val c = Context()
        pairs.forEach { (k, v) -> c.set(k, v) }
        return c
    }

    fun outcome(status: StageStatus, preferred: String = ""): Outcome =
        Outcome(status = status, preferredLabel = preferred)

    test("empty condition is always true") {
        ConditionEvaluator.evaluate("", outcome(StageStatus.SUCCESS), ctx()) shouldBe true
        ConditionEvaluator.evaluate("  ", outcome(StageStatus.FAIL), ctx()) shouldBe true
    }

    test("outcome=success matches success") {
        ConditionEvaluator.evaluate("outcome=success", outcome(StageStatus.SUCCESS), ctx()) shouldBe true
        ConditionEvaluator.evaluate("outcome=success", outcome(StageStatus.FAIL), ctx()) shouldBe false
    }

    test("outcome=fail matches fail") {
        ConditionEvaluator.evaluate("outcome=fail", outcome(StageStatus.FAIL), ctx()) shouldBe true
        ConditionEvaluator.evaluate("outcome=fail", outcome(StageStatus.SUCCESS), ctx()) shouldBe false
    }

    test("outcome!=success matches non-success") {
        ConditionEvaluator.evaluate("outcome!=success", outcome(StageStatus.FAIL), ctx()) shouldBe true
        ConditionEvaluator.evaluate("outcome!=success", outcome(StageStatus.SUCCESS), ctx()) shouldBe false
    }

    test("preferred_label matching") {
        ConditionEvaluator.evaluate(
            "preferred_label=Fix",
            outcome(StageStatus.SUCCESS, preferred = "Fix"),
            ctx()
        ) shouldBe true

        ConditionEvaluator.evaluate(
            "preferred_label=Fix",
            outcome(StageStatus.SUCCESS, preferred = "Deploy"),
            ctx()
        ) shouldBe false
    }

    test("context.* key lookup") {
        val c = ctx("tests_passed" to "true")
        ConditionEvaluator.evaluate("context.tests_passed=true", outcome(StageStatus.SUCCESS), c) shouldBe true
        ConditionEvaluator.evaluate("context.tests_passed=false", outcome(StageStatus.SUCCESS), c) shouldBe false
    }

    test("context.* key missing returns empty string") {
        val c = ctx()
        ConditionEvaluator.evaluate("context.missing_key=something", outcome(StageStatus.SUCCESS), c) shouldBe false
        ConditionEvaluator.evaluate("context.missing_key!=something", outcome(StageStatus.SUCCESS), c) shouldBe true
    }

    test("AND conjunction (&&)") {
        val c = ctx("tests_passed" to "true")
        ConditionEvaluator.evaluate(
            "outcome=success && context.tests_passed=true",
            outcome(StageStatus.SUCCESS),
            c
        ) shouldBe true

        ConditionEvaluator.evaluate(
            "outcome=success && context.tests_passed=false",
            outcome(StageStatus.SUCCESS),
            c
        ) shouldBe false

        ConditionEvaluator.evaluate(
            "outcome=fail && context.tests_passed=true",
            outcome(StageStatus.SUCCESS),
            c
        ) shouldBe false
    }

    test("multiple AND clauses all must pass") {
        val c = ctx("a" to "1", "b" to "2", "c" to "3")
        ConditionEvaluator.evaluate(
            "context.a=1 && context.b=2 && context.c=3",
            outcome(StageStatus.SUCCESS),
            c
        ) shouldBe true

        ConditionEvaluator.evaluate(
            "context.a=1 && context.b=2 && context.c=WRONG",
            outcome(StageStatus.SUCCESS),
            c
        ) shouldBe false
    }

    test("context key without prefix") {
        val c = ctx("loop_state" to "active")
        ConditionEvaluator.evaluate("context.loop_state!=exhausted", outcome(StageStatus.SUCCESS), c) shouldBe true
        ConditionEvaluator.evaluate("context.loop_state=active", outcome(StageStatus.SUCCESS), c) shouldBe true
    }

    test("route on success from spec example") {
        ConditionEvaluator.evaluate(
            "outcome=success",
            outcome(StageStatus.SUCCESS),
            ctx()
        ) shouldBe true
    }

    test("route on failure from spec example") {
        ConditionEvaluator.evaluate(
            "outcome=fail",
            outcome(StageStatus.FAIL),
            ctx()
        ) shouldBe true
    }

    test("combined condition from spec example") {
        val c = ctx("tests_passed" to "true")
        ConditionEvaluator.evaluate(
            "outcome=success && context.tests_passed=true",
            outcome(StageStatus.SUCCESS),
            c
        ) shouldBe true
    }
})
