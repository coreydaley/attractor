package attractor.dot

// ─── Parser ───────────────────────────────────────────────────────────────────

class ParseException(message: String) : Exception(message)

class Parser(private val tokens: List<Token>) {
    private var pos = 0

    private fun current(): Token = tokens[pos]
    private fun peek(offset: Int = 1): Token =
        if (pos + offset < tokens.size) tokens[pos + offset] else tokens.last()

    private fun advance(): Token = tokens[pos++]

    private fun expect(type: TokenType): Token {
        val tok = current()
        if (tok.type != type) {
            throw ParseException(
                "Expected $type but got ${tok.type}('${tok.value}') at line ${tok.line}:${tok.col}"
            )
        }
        return advance()
    }

    private fun match(vararg types: TokenType): Boolean =
        current().type in types

    private fun skip(vararg types: TokenType) {
        while (current().type in types) advance()
    }

    // ── Top-level parse ──────────────────────────────────────────────────────

    fun parse(): DotGraph {
        expect(TokenType.DIGRAPH)

        // Optional graph ID
        val graphId = if (match(TokenType.IDENTIFIER)) advance().value else "G"

        expect(TokenType.LBRACE)

        val graph = DotGraph(id = graphId)

        // Default attr stacks
        val nodeDefaults = mutableMapOf<String, DotValue>()
        val edgeDefaults = mutableMapOf<String, DotValue>()

        parseStatements(graph, nodeDefaults, edgeDefaults)

        expect(TokenType.RBRACE)

        // Apply end-of-file check
        if (current().type != TokenType.EOF) {
            // Might have trailing content; ignore
        }

        return graph
    }

    private fun parseStatements(
        graph: DotGraph,
        nodeDefaults: MutableMap<String, DotValue>,
        edgeDefaults: MutableMap<String, DotValue>
    ) {
        while (!match(TokenType.RBRACE, TokenType.EOF)) {
            parseStatement(graph, nodeDefaults, edgeDefaults)
            skip(TokenType.SEMI)
        }
    }

    private fun parseStatement(
        graph: DotGraph,
        nodeDefaults: MutableMap<String, DotValue>,
        edgeDefaults: MutableMap<String, DotValue>
    ) {
        when {
            // graph [ ... ]
            match(TokenType.GRAPH) -> {
                advance()
                if (match(TokenType.LBRACKET)) {
                    val attrs = parseAttrBlock()
                    graph.attrs.putAll(attrs)
                } else if (match(TokenType.EQUALS)) {
                    // graph-level key = value like: rankdir = LR
                    // This case is actually handled below in the IDENTIFIER branch
                }
            }
            // node [ ... ]
            match(TokenType.NODE) -> {
                advance()
                if (match(TokenType.LBRACKET)) {
                    nodeDefaults.putAll(parseAttrBlock())
                }
            }
            // edge [ ... ]
            match(TokenType.EDGE) -> {
                advance()
                if (match(TokenType.LBRACKET)) {
                    edgeDefaults.putAll(parseAttrBlock())
                }
            }
            // subgraph
            match(TokenType.SUBGRAPH) -> {
                parseSubgraph(graph, nodeDefaults, edgeDefaults)
            }
            // identifier = value (graph-level attr decl like rankdir=LR)
            match(TokenType.IDENTIFIER) && peek().type == TokenType.EQUALS &&
                    peek(2).type !in setOf(TokenType.ARROW) -> {
                val key = advance().value
                advance() // =
                val value = parseValue()
                graph.attrs[key] = value
            }
            // node statement or edge statement
            match(TokenType.IDENTIFIER) -> {
                parseNodeOrEdge(graph, nodeDefaults, edgeDefaults)
            }
            else -> {
                // Skip unknown token
                advance()
            }
        }
    }

    private fun parseSubgraph(
        graph: DotGraph,
        nodeDefaults: MutableMap<String, DotValue>,
        edgeDefaults: MutableMap<String, DotValue>
    ) {
        expect(TokenType.SUBGRAPH)
        // Optional subgraph ID
        val subgraphLabel = if (match(TokenType.IDENTIFIER)) advance().value else null

        // Subgraph inherits defaults but can override them
        val localNodeDefaults = nodeDefaults.toMutableMap()
        val localEdgeDefaults = edgeDefaults.toMutableMap()

        expect(TokenType.LBRACE)

        // Derive class from subgraph label
        val derivedClass = subgraphLabel?.let { deriveClass(it) }

        // Parse subgraph attributes and statements
        while (!match(TokenType.RBRACE, TokenType.EOF)) {
            when {
                match(TokenType.GRAPH) -> {
                    // subgraph-level label or attrs
                    advance()
                    if (match(TokenType.LBRACKET)) {
                        val attrs = parseAttrBlock()
                        // Apply as subgraph defaults if relevant - ignored for now
                    } else if (match(TokenType.EQUALS)) {
                        // skip
                    }
                }
                match(TokenType.IDENTIFIER) && peek().type == TokenType.EQUALS -> {
                    // subgraph-level attr like label = "..."
                    val key = advance().value
                    advance() // =
                    val value = parseValue()
                    // Store as subgraph label
                    if (key == "label") {
                        // Use this as the class name for nodes in this subgraph
                        val cls = deriveClass(value.asString())
                        localNodeDefaults["__subgraph_class"] = DotValue.StringValue(cls)
                    }
                }
                match(TokenType.NODE) -> {
                    advance()
                    if (match(TokenType.LBRACKET)) {
                        localNodeDefaults.putAll(parseAttrBlock())
                    }
                }
                match(TokenType.EDGE) -> {
                    advance()
                    if (match(TokenType.LBRACKET)) {
                        localEdgeDefaults.putAll(parseAttrBlock())
                    }
                }
                match(TokenType.SUBGRAPH) -> {
                    parseSubgraph(graph, localNodeDefaults, localEdgeDefaults)
                }
                match(TokenType.IDENTIFIER) -> {
                    parseNodeOrEdge(graph, localNodeDefaults, localEdgeDefaults)
                }
                else -> advance()
            }
            skip(TokenType.SEMI)
        }

        expect(TokenType.RBRACE)
    }

    private fun deriveClass(label: String): String =
        label.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), "")

    private fun parseNodeOrEdge(
        graph: DotGraph,
        nodeDefaults: MutableMap<String, DotValue>,
        edgeDefaults: MutableMap<String, DotValue>
    ) {
        val firstId = advance().value

        // Check for edge (chain of -> targets)
        if (match(TokenType.ARROW)) {
            // Edge statement
            val ids = mutableListOf(firstId)
            while (match(TokenType.ARROW)) {
                advance() // ->
                ids.add(expect(TokenType.IDENTIFIER).value)
            }
            val edgeAttrs = if (match(TokenType.LBRACKET)) {
                parseAttrBlock()
            } else {
                mutableMapOf()
            }
            // Expand chained edges
            for (i in 0 until ids.size - 1) {
                val from = ids[i]
                val to = ids[i + 1]
                val merged = edgeDefaults.toMutableMap()
                merged.putAll(edgeAttrs)
                graph.edges.add(DotEdge(from = from, to = to, attrs = merged))
                // Ensure nodes exist
                ensureNode(graph, from, nodeDefaults)
                ensureNode(graph, to, nodeDefaults)
            }
        } else {
            // Node statement
            val nodeAttrs = if (match(TokenType.LBRACKET)) {
                parseAttrBlock()
            } else {
                mutableMapOf()
            }
            val node = ensureNode(graph, firstId, nodeDefaults)
            // Explicit attrs override defaults
            node.attrs.putAll(nodeAttrs)
        }
    }

    private fun ensureNode(
        graph: DotGraph,
        id: String,
        defaults: MutableMap<String, DotValue>
    ): DotNode {
        return graph.nodes.getOrPut(id) {
            val subgraphClass = defaults["__subgraph_class"]?.asString()
            val attrs = defaults.filter { it.key != "__subgraph_class" }.toMutableMap()
            // If node has a class already don't override, else use subgraph class
            if (subgraphClass != null && !attrs.containsKey("class")) {
                attrs["class"] = DotValue.StringValue(subgraphClass)
            }
            DotNode(id = id, attrs = attrs)
        }
    }

    private fun parseAttrBlock(): MutableMap<String, DotValue> {
        expect(TokenType.LBRACKET)
        val attrs = mutableMapOf<String, DotValue>()
        while (!match(TokenType.RBRACKET, TokenType.EOF)) {
            val key = parseKey()
            expect(TokenType.EQUALS)
            val value = parseValue()
            attrs[key] = value
            // Optional comma separator
            if (match(TokenType.COMMA)) advance()
        }
        expect(TokenType.RBRACKET)
        return attrs
    }

    private fun parseKey(): String {
        val first = when {
            match(TokenType.IDENTIFIER) -> advance().value
            match(TokenType.GRAPH, TokenType.NODE, TokenType.EDGE) -> advance().value
            else -> throw ParseException("Expected key at line ${current().line}:${current().col}, got ${current().type}")
        }
        // Qualified key: a.b.c
        return if (match(TokenType.DOT)) {
            val sb = StringBuilder(first)
            while (match(TokenType.DOT)) {
                advance() // .
                sb.append('.')
                sb.append(
                    when {
                        match(TokenType.IDENTIFIER) -> advance().value
                        else -> throw ParseException("Expected identifier after '.' at ${current().line}:${current().col}")
                    }
                )
            }
            sb.toString()
        } else {
            first
        }
    }

    private val durationRegex = Regex("""^(\d+)(ms|s|m|h|d)$""")

    private fun parseDurationString(str: String): DotValue.DurationValue? {
        val match = durationRegex.matchEntire(str) ?: return null
        val num = match.groupValues[1].toLong()
        val unit = match.groupValues[2]
        val millis = when (unit) {
            "ms" -> num
            "s"  -> num * 1000
            "m"  -> num * 60_000
            "h"  -> num * 3_600_000
            "d"  -> num * 86_400_000
            else -> num
        }
        return DotValue.DurationValue(millis, str)
    }

    private fun parseValue(): DotValue {
        return when (current().type) {
            TokenType.STRING -> {
                val tok = advance()
                // Check if the string value is a duration like "900s", "15m", "2h", "250ms"
                parseDurationString(tok.value) ?: DotValue.StringValue(tok.value)
            }
            TokenType.INTEGER -> {
                val tok = advance()
                DotValue.IntegerValue(tok.value.toLong())
            }
            TokenType.FLOAT -> {
                val tok = advance()
                DotValue.FloatValue(tok.value.toDouble())
            }
            TokenType.BOOLEAN -> {
                val tok = advance()
                DotValue.BooleanValue(tok.value == "true")
            }
            TokenType.DURATION -> {
                val tok = advance()
                // Parse duration
                val str = tok.value
                val unitStart = str.indexOfFirst { it.isLetter() }
                val num = str.substring(0, unitStart).toLong()
                val unit = str.substring(unitStart)
                val millis = when (unit) {
                    "ms" -> num
                    "s"  -> num * 1000
                    "m"  -> num * 60_000
                    "h"  -> num * 3_600_000
                    "d"  -> num * 86_400_000
                    else -> num
                }
                DotValue.DurationValue(millis, str)
            }
            TokenType.IDENTIFIER -> {
                // Bare identifier used as value (e.g., shape=box, rankdir=LR)
                val tok = advance()
                DotValue.StringValue(tok.value)
            }
            TokenType.GRAPH, TokenType.NODE, TokenType.EDGE, TokenType.SUBGRAPH -> {
                // Keywords used as values
                val tok = advance()
                DotValue.StringValue(tok.value)
            }
            else -> throw ParseException(
                "Expected value but got ${current().type}('${current().value}') at line ${current().line}:${current().col}"
            )
        }
    }

    companion object {
        fun parse(dotSource: String): DotGraph {
            val tokens = Lexer(dotSource).tokenize()
            return Parser(tokens).parse()
        }
    }
}
