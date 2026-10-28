package com.evcompanion.core.preset.formula

import kotlin.math.max

/**
 * A parsed Torque custom-PID equation, ready to evaluate repeatedly against either raw
 * response bytes (for OBD-backed rows) or a map of already-resolved named values (for
 * calculated/`val{}` rows) - see [usesRawBytes].
 */
class Formula private constructor(
    private val root: FormulaNode,
    val rawEquation: String,
) {
    /** True if this formula reads byte offsets / bit flags from a raw OBD response. */
    val usesRawBytes: Boolean = containsByteAccess(root)

    /** Full PID names this formula references via `val{Name}`, for dependency ordering. */
    val referencedPidNames: Set<String> = collectValRefs(root)

    /**
     * @param bytes required if [usesRawBytes] is true.
     * @param resolved already-computed values for any `val{Name}` references this formula uses.
     */
    fun evaluate(bytes: ByteArray? = null, resolved: Map<String, Double> = emptyMap()): Double =
        eval(root, bytes, resolved)

    class UnresolvedReferenceException(val pidName: String) :
        Exception("Formula references unresolved PID '$pidName'")

    companion object {
        fun parse(equation: String): Formula = Formula(FormulaParser.parse(equation), equation)

        private fun eval(node: FormulaNode, bytes: ByteArray?, resolved: Map<String, Double>): Double =
            when (node) {
                is FormulaNode.NumberLiteral -> node.value

                is FormulaNode.ByteVar -> {
                    val b = requireBytes(bytes, node.letters)
                    require(node.index < b.size) {
                        "Byte '${node.letters}' (offset ${node.index}) is out of range for a ${b.size}-byte response"
                    }
                    (b[node.index].toInt() and 0xFF).toDouble()
                }

                is FormulaNode.BitExtract -> {
                    val b = requireBytes(bytes, "{${node.byteIndex}:${node.bit}}")
                    require(node.byteIndex < b.size) { "Bit-extract byte offset ${node.byteIndex} out of range" }
                    val byteVal = b[node.byteIndex].toInt() and 0xFF
                    ((byteVal shr node.bit) and 1).toDouble()
                }

                is FormulaNode.ValRef ->
                    resolved[node.pidName] ?: throw UnresolvedReferenceException(node.pidName)

                is FormulaNode.UnaryMinus -> -eval(node.node, bytes, resolved)

                is FormulaNode.BinaryOp -> {
                    val l = eval(node.left, bytes, resolved)
                    val r = eval(node.right, bytes, resolved)
                    when (node.op) {
                        '+' -> l + r
                        '-' -> l - r
                        '*' -> l * r
                        '/' -> l / r
                        '<' -> (l.toInt() shl r.toInt()).toDouble()
                        else -> error("Unknown operator '${node.op}'")
                    }
                }

                is FormulaNode.FuncCall -> evalFunc(node, bytes, resolved)
            }

        private fun evalFunc(node: FormulaNode.FuncCall, bytes: ByteArray?, resolved: Map<String, Double>): Double {
            val args = node.args.map { eval(it, bytes, resolved) }
            return when (node.name) {
                "SIGNED" -> {
                    require(args.size == 1) { "SIGNED() takes 1 argument" }
                    args[0].toInt().toByte().toInt().toDouble()
                }
                "INT24" -> {
                    require(args.size == 3) { "Int24() takes 3 arguments" }
                    val a = args[0].toInt() and 0xFF
                    val b = args[1].toInt() and 0xFF
                    val c = args[2].toInt() and 0xFF
                    ((a shl 16) or (b shl 8) or c).toDouble()
                }
                "MAX" -> {
                    require(args.size == 2) { "MAX() takes 2 arguments" }
                    max(args[0], args[1])
                }
                else -> error("Unknown function '${node.name}' in formula")
            }
        }

        private fun requireBytes(bytes: ByteArray?, what: String): ByteArray =
            bytes ?: throw IllegalStateException("Formula needs response bytes to evaluate '$what' but none were supplied")

        private fun containsByteAccess(node: FormulaNode): Boolean = when (node) {
            is FormulaNode.ByteVar, is FormulaNode.BitExtract -> true
            is FormulaNode.NumberLiteral, is FormulaNode.ValRef -> false
            is FormulaNode.UnaryMinus -> containsByteAccess(node.node)
            is FormulaNode.BinaryOp -> containsByteAccess(node.left) || containsByteAccess(node.right)
            is FormulaNode.FuncCall -> node.args.any { containsByteAccess(it) }
        }

        private fun collectValRefs(node: FormulaNode): Set<String> = when (node) {
            is FormulaNode.ValRef -> setOf(node.pidName)
            is FormulaNode.NumberLiteral, is FormulaNode.ByteVar, is FormulaNode.BitExtract -> emptySet()
            is FormulaNode.UnaryMinus -> collectValRefs(node.node)
            is FormulaNode.BinaryOp -> collectValRefs(node.left) + collectValRefs(node.right)
            is FormulaNode.FuncCall -> node.args.flatMap { collectValRefs(it) }.toSet()
        }
    }
}
