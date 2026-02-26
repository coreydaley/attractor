package attractor.handlers

import attractor.dot.DotNode

/**
 * Shape-to-handler-type mapping (Section 2.8).
 */
val SHAPE_TO_TYPE = mapOf(
    "Mdiamond"       to "start",
    "Msquare"        to "exit",
    "box"            to "codergen",
    "hexagon"        to "wait.human",
    "diamond"        to "conditional",
    "component"      to "parallel",
    "tripleoctagon"  to "parallel.fan_in",
    "parallelogram"  to "tool",
    "house"          to "stack.manager_loop"
)

class HandlerRegistry(
    private val defaultHandler: Handler
) {
    private val handlers: MutableMap<String, Handler> = mutableMapOf()

    fun register(typeString: String, handler: Handler) {
        handlers[typeString] = handler
    }

    fun resolve(node: DotNode): Handler {
        // 1. Explicit type attribute
        if (node.type.isNotBlank() && handlers.containsKey(node.type)) {
            val h = handlers[node.type]!!
            // tool handler requires tool_command — fall back if absent
            if (h === ToolHandler && node.attrOrDefault("tool_command", "").isBlank()) return defaultHandler
            return h
        }

        // 2. Shape-based resolution
        val shapeType = SHAPE_TO_TYPE[node.shape]
        if (shapeType != null && handlers.containsKey(shapeType)) {
            val h = handlers[shapeType]!!
            // tool handler requires tool_command — fall back if absent
            if (h === ToolHandler && node.attrOrDefault("tool_command", "").isBlank()) return defaultHandler
            return h
        }

        // 3. Default
        return defaultHandler
    }

    companion object {
        fun createDefault(
            codergenHandler: Handler,
            interviewer: attractor.human.Interviewer? = null
        ): HandlerRegistry {
            val registry = HandlerRegistry(defaultHandler = codergenHandler)
            registry.register("start", StartHandler)
            registry.register("exit", ExitHandler)
            registry.register("codergen", codergenHandler)
            registry.register("conditional", ConditionalHandler)
            if (interviewer != null) {
                registry.register("wait.human", WaitForHumanHandler(interviewer))
            } else {
                registry.register("wait.human", WaitForHumanHandler(attractor.human.AutoApproveInterviewer()))
            }
            registry.register("parallel", ParallelHandler(registry))
            registry.register("parallel.fan_in", FanInHandler())
            registry.register("tool", ToolHandler)
            registry.register("stack.manager_loop", ManagerLoopHandler())
            return registry
        }
    }
}
