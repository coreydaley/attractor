package attractor.transform

import attractor.dot.DotGraph
import attractor.style.Stylesheet

/**
 * Applies the model_stylesheet graph attribute to nodes (Section 8, 9.2).
 */
class StylesheetApplicationTransform : Transform {
    override fun apply(graph: DotGraph): DotGraph {
        val css = graph.modelStylesheet
        if (css.isNotBlank()) {
            Stylesheet.applyToGraph(graph, css)
        }
        return graph
    }
}
