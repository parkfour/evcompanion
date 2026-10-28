package com.evcompanion.core.preset.formula

internal sealed class FormulaNode {
    data class NumberLiteral(val value: Double) : FormulaNode()
    data class ByteVar(val index: Int, val letters: String) : FormulaNode()
    data class BitExtract(val byteIndex: Int, val bit: Int) : FormulaNode()
    data class ValRef(val pidName: String) : FormulaNode()
    data class FuncCall(val name: String, val args: List<FormulaNode>) : FormulaNode()
    data class BinaryOp(val op: Char, val left: FormulaNode, val right: FormulaNode) : FormulaNode()
    data class UnaryMinus(val node: FormulaNode) : FormulaNode()
}

/** Spreadsheet-style column decode: a=0, b=1, ..., z=25, aa=26, ab=27, ..., az=51, ba=52, ... */
internal fun byteLettersToIndex(letters: String): Int {
    var value = 0
    for (c in letters) {
        require(c in 'a'..'z') { "Not a valid byte-variable name: $letters" }
        value = value * 26 + (c - 'a' + 1)
    }
    return value - 1
}
