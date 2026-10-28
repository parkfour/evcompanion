package com.evcompanion.core.preset.formula

internal sealed class Token {
    data class Number(val value: Double) : Token()
    data class Ident(val name: String) : Token()
    data class RawText(val text: String) : Token()
    data object LParen : Token()
    data object RParen : Token()
    data object LBrace : Token()
    data object RBrace : Token()
    data object Plus : Token()
    data object Minus : Token()
    data object Star : Token()
    data object Slash : Token()
    data object Lt : Token()
    data object Colon : Token()
    data object Eof : Token()
}

/**
 * Tokenizes a Torque custom-PID "Equation" string. Content inside `{...}` (bit extraction, or
 * a `val{Full PID Name}` cross-reference) is captured verbatim as a single [Token.RawText]
 * rather than tokenized as arithmetic, since PID names contain spaces/underscores that aren't
 * otherwise part of the grammar.
 */
internal object FormulaLexer {

    class FormulaSyntaxException(message: String) : Exception(message)

    fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { tokens += Token.LParen; i++ }
                c == ')' -> { tokens += Token.RParen; i++ }
                c == '{' -> {
                    val end = input.indexOf('}', startIndex = i + 1)
                    if (end == -1) throw FormulaSyntaxException("Unmatched '{' in: $input")
                    tokens += Token.LBrace
                    tokens += Token.RawText(input.substring(i + 1, end).trim())
                    tokens += Token.RBrace
                    i = end + 1
                }
                c == '}' -> throw FormulaSyntaxException("Unmatched '}' in: $input")
                c == '+' -> { tokens += Token.Plus; i++ }
                c == '-' -> { tokens += Token.Minus; i++ }
                c == '*' -> { tokens += Token.Star; i++ }
                c == '/' -> { tokens += Token.Slash; i++ }
                c == '<' -> { tokens += Token.Lt; i++ }
                c == ':' -> { tokens += Token.Colon; i++ }
                c.isDigit() || (c == '.' && i + 1 < input.length && input[i + 1].isDigit()) -> {
                    val start = i
                    while (i < input.length && (input[i].isDigit() || input[i] == '.')) i++
                    tokens += Token.Number(input.substring(start, i).toDouble())
                }
                c.isLetter() -> {
                    // Function names like Int24 contain digits after the leading letters;
                    // byte-variable names (a, b, ..., aa, ab, ...) never do.
                    val start = i
                    while (i < input.length && input[i].isLetterOrDigit()) i++
                    tokens += Token.Ident(input.substring(start, i))
                }
                else -> throw FormulaSyntaxException("Unexpected character '$c' in: $input")
            }
        }
        tokens += Token.Eof
        return tokens
    }
}
