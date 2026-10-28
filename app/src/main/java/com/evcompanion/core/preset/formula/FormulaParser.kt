package com.evcompanion.core.preset.formula

/**
 * Recursive-descent parser for the Torque custom-PID "Equation" dialect. Operator precedence
 * (low to high): shift-left `<` (custom to this dialect - combines bytes, e.g. `(m<8)+n`),
 * then `+ -`, then `* /`, then unary minus. Every occurrence of `<` in the reference IONIQ 5
 * CSV is fully parenthesized, so this precedence choice (matching C's `<<` placement) doesn't
 * affect those formulas either way - it's a documented default for hand-authored presets.
 */
internal object FormulaParser {

    fun parse(input: String): FormulaNode {
        val tokens = FormulaLexer.tokenize(input)
        val parser = Parser(tokens)
        val node = parser.parseExpr()
        parser.expect<Token.Eof>("end of formula")
        return node
    }

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        private fun peek(): Token = tokens[pos]
        private fun advance(): Token = tokens[pos++]

        inline fun <reified T : Token> expect(what: String): T {
            val t = advance()
            return t as? T ?: throw FormulaLexer.FormulaSyntaxException("Expected $what, got $t")
        }

        fun parseExpr(): FormulaNode = parseShift()

        private fun parseShift(): FormulaNode {
            var left = parseAdd()
            while (peek() is Token.Lt) {
                advance()
                left = FormulaNode.BinaryOp('<', left, parseAdd())
            }
            return left
        }

        private fun parseAdd(): FormulaNode {
            var left = parseMul()
            while (true) {
                left = when (peek()) {
                    is Token.Plus -> { advance(); FormulaNode.BinaryOp('+', left, parseMul()) }
                    is Token.Minus -> { advance(); FormulaNode.BinaryOp('-', left, parseMul()) }
                    else -> return left
                }
            }
        }

        private fun parseMul(): FormulaNode {
            var left = parseUnary()
            while (true) {
                left = when (peek()) {
                    is Token.Star -> { advance(); FormulaNode.BinaryOp('*', left, parseUnary()) }
                    is Token.Slash -> { advance(); FormulaNode.BinaryOp('/', left, parseUnary()) }
                    else -> return left
                }
            }
        }

        private fun parseUnary(): FormulaNode =
            if (peek() is Token.Minus) {
                advance()
                FormulaNode.UnaryMinus(parseUnary())
            } else {
                parsePrimary()
            }

        private fun parsePrimary(): FormulaNode {
            return when (val t = peek()) {
                is Token.Number -> { advance(); FormulaNode.NumberLiteral(t.value) }
                is Token.LParen -> {
                    advance()
                    val inner = parseExpr()
                    expect<Token.RParen>("')'")
                    inner
                }
                is Token.LBrace -> {
                    advance()
                    val raw = expect<Token.RawText>("bit-extract contents").text
                    expect<Token.RBrace>("'}'")
                    parseBitExtract(raw)
                }
                is Token.Ident -> {
                    advance()
                    parseIdentContinuation(t.name)
                }
                else -> throw FormulaLexer.FormulaSyntaxException("Unexpected token $t")
            }
        }

        private fun parseIdentContinuation(name: String): FormulaNode {
            val upper = name.uppercase()
            return when {
                upper == "VAL" && peek() is Token.LBrace -> {
                    advance()
                    val raw = expect<Token.RawText>("val{} contents").text
                    expect<Token.RBrace>("'}'")
                    FormulaNode.ValRef(raw.trim())
                }
                peek() is Token.LParen -> {
                    advance()
                    val args = mutableListOf(parseExpr())
                    while (peek() is Token.Colon) {
                        advance()
                        args += parseExpr()
                    }
                    expect<Token.RParen>("')'")
                    FormulaNode.FuncCall(upper, args)
                }
                else -> {
                    val index = byteLettersToIndex(name.lowercase())
                    FormulaNode.ByteVar(index, name.lowercase())
                }
            }
        }

        private fun parseBitExtract(raw: String): FormulaNode {
            val parts = raw.split(':').map { it.trim() }
            require(parts.size == 2) { "Expected byte:bit inside {}, got: $raw" }
            val byteIndex = byteLettersToIndex(parts[0].lowercase())
            val bit = parts[1].toIntOrNull()
                ?: throw FormulaLexer.FormulaSyntaxException("Expected an integer bit index in {$raw}")
            return FormulaNode.BitExtract(byteIndex, bit)
        }
    }
}
