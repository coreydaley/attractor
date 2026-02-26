package attractor.dot

// ─── Token Types ─────────────────────────────────────────────────────────────

enum class TokenType {
    // Keywords
    DIGRAPH, GRAPH, NODE, EDGE, SUBGRAPH,
    // Punctuation
    LBRACE, RBRACE, LBRACKET, RBRACKET,
    ARROW, SEMI, COMMA, EQUALS, DOT,
    // Values
    IDENTIFIER, STRING, INTEGER, FLOAT, BOOLEAN, DURATION,
    // Special
    EOF
}

data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val col: Int
)

// ─── Lexer ───────────────────────────────────────────────────────────────────

class Lexer(private val source: String) {
    private var pos = 0
    private var line = 1
    private var col = 1

    private val stripped: String = stripComments(source)
    private val input: String = stripped

    companion object {
        private val KEYWORDS = setOf("digraph", "graph", "node", "edge", "subgraph", "true", "false")
        private val DURATION_UNITS = setOf("ms", "s", "m", "h", "d")

        fun stripComments(src: String): String {
            val sb = StringBuilder()
            var i = 0
            var inString = false
            while (i < src.length) {
                if (inString) {
                    // Inside a quoted string: only watch for escape sequences and closing quote
                    if (src[i] == '\\' && i + 1 < src.length) {
                        sb.append(src[i]); sb.append(src[i + 1]); i += 2
                    } else if (src[i] == '"') {
                        inString = false; sb.append(src[i]); i++
                    } else {
                        sb.append(src[i]); i++
                    }
                } else if (i + 1 < src.length && src[i] == '/' && src[i + 1] == '/') {
                    // Line comment: skip to end of line
                    while (i < src.length && src[i] != '\n') i++
                } else if (i + 1 < src.length && src[i] == '/' && src[i + 1] == '*') {
                    // Block comment: skip until */
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) {
                        if (src[i] == '\n') sb.append('\n')
                        i++
                    }
                    if (i + 1 < src.length) i += 2 // skip */
                } else {
                    if (src[i] == '"') inString = true
                    sb.append(src[i]); i++
                }
            }
            return sb.toString()
        }
    }

    private fun current(): Char = if (pos < input.length) input[pos] else '\u0000'
    private fun peek(offset: Int = 1): Char = if (pos + offset < input.length) input[pos + offset] else '\u0000'

    private fun advance(): Char {
        val ch = current()
        pos++
        if (ch == '\n') { line++; col = 1 } else col++
        return ch
    }

    private fun skipWhitespace() {
        while (pos < input.length && current().isWhitespace()) advance()
    }

    private fun readString(): Token {
        val startLine = line
        val startCol = col
        advance() // skip opening "
        val sb = StringBuilder()
        while (pos < input.length && current() != '"') {
            if (current() == '\\') {
                advance()
                when (current()) {
                    '"'  -> sb.append('"')
                    'n'  -> sb.append('\n')
                    't'  -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    else -> { sb.append('\\'); sb.append(current()) }
                }
                advance()
            } else {
                sb.append(advance())
            }
        }
        if (pos < input.length) advance() // skip closing "
        return Token(TokenType.STRING, sb.toString(), startLine, startCol)
    }

    private fun readNumber(startLine: Int, startCol: Int): Token {
        val sb = StringBuilder()
        var isFloat = false
        if (current() == '-') sb.append(advance())
        while (pos < input.length && current().isDigit()) sb.append(advance())

        // Check for decimal point
        if (current() == '.' && peek().isDigit()) {
            isFloat = true
            sb.append(advance()) // .
            while (pos < input.length && current().isDigit()) sb.append(advance())
        }

        // Check for duration suffix
        val numStr = sb.toString()
        if (!isFloat) {
            val suffix = StringBuilder()
            // Peek at potential suffix (ms, s, m, h, d)
            val savedPos = pos
            val savedLine = line
            val savedCol = col
            while (pos < input.length && current().isLetter()) suffix.append(advance())
            val sfx = suffix.toString()
            if (sfx in DURATION_UNITS) {
                val original = numStr + sfx
                val num = numStr.toLong()
                val millis = when (sfx) {
                    "ms" -> num
                    "s"  -> num * 1000
                    "m"  -> num * 60_000
                    "h"  -> num * 3_600_000
                    "d"  -> num * 86_400_000
                    else -> num
                }
                return Token(TokenType.DURATION, original, startLine, startCol)
            } else {
                // Not a duration suffix: restore position
                pos = savedPos; line = savedLine; col = savedCol
            }
        }

        return if (isFloat) {
            Token(TokenType.FLOAT, numStr, startLine, startCol)
        } else {
            Token(TokenType.INTEGER, numStr, startLine, startCol)
        }
    }

    private fun readIdentifier(startLine: Int, startCol: Int): Token {
        val sb = StringBuilder()
        while (pos < input.length && (current().isLetterOrDigit() || current() == '_')) {
            sb.append(advance())
        }
        val word = sb.toString()
        return when (word.lowercase()) {
            "digraph"  -> Token(TokenType.DIGRAPH, word, startLine, startCol)
            "graph"    -> Token(TokenType.GRAPH, word, startLine, startCol)
            "node"     -> Token(TokenType.NODE, word, startLine, startCol)
            "edge"     -> Token(TokenType.EDGE, word, startLine, startCol)
            "subgraph" -> Token(TokenType.SUBGRAPH, word, startLine, startCol)
            "true"     -> Token(TokenType.BOOLEAN, "true", startLine, startCol)
            "false"    -> Token(TokenType.BOOLEAN, "false", startLine, startCol)
            else       -> Token(TokenType.IDENTIFIER, word, startLine, startCol)
        }
    }

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            skipWhitespace()
            if (pos >= input.length) {
                tokens.add(Token(TokenType.EOF, "", line, col))
                break
            }
            val startLine = line
            val startCol = col
            val ch = current()

            val token = when {
                ch == '"' -> readString()
                ch == '{' -> { advance(); Token(TokenType.LBRACE, "{", startLine, startCol) }
                ch == '}' -> { advance(); Token(TokenType.RBRACE, "}", startLine, startCol) }
                ch == '[' -> { advance(); Token(TokenType.LBRACKET, "[", startLine, startCol) }
                ch == ']' -> { advance(); Token(TokenType.RBRACKET, "]", startLine, startCol) }
                ch == ';' -> { advance(); Token(TokenType.SEMI, ";", startLine, startCol) }
                ch == ',' -> { advance(); Token(TokenType.COMMA, ",", startLine, startCol) }
                ch == '=' -> { advance(); Token(TokenType.EQUALS, "=", startLine, startCol) }
                ch == '.' -> { advance(); Token(TokenType.DOT, ".", startLine, startCol) }
                ch == '-' && peek() == '>' -> { advance(); advance(); Token(TokenType.ARROW, "->", startLine, startCol) }
                ch == '-' && peek().isDigit() -> readNumber(startLine, startCol)
                ch.isDigit() -> readNumber(startLine, startCol)
                ch.isLetter() || ch == '_' -> readIdentifier(startLine, startCol)
                else -> { advance(); null } // skip unknown chars
            }
            if (token != null) tokens.add(token)
        }
        return tokens
    }
}
