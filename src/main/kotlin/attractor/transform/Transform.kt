package attractor.transform

import attractor.dot.DotGraph

interface Transform {
    fun apply(graph: DotGraph): DotGraph
}
