package com.evcompanion.core.preset

import com.evcompanion.core.preset.formula.Formula

/**
 * Parses the Torque custom-PID CSV dialect (`Name,ShortName,ModeAndPID,Equation,Min Value,
 * Max Value,Units,Header`). Comment lines (`~...`) and blank lines are skipped. A row whose
 * formula fails to parse (community CSVs are hand-edited and occasionally have typos - e.g.
 * unbalanced parens) is skipped rather than failing the whole preset; skipped rows and the
 * reason are reported in [ParseResult.warnings].
 */
object TorquePresetParser {

    data class ParseResult(val rows: List<PidRow>, val warnings: List<String>)

    fun parse(csvText: String): ParseResult {
        val warnings = mutableListOf<String>()
        val rows = mutableListOf<PidRow>()
        var headerRowConsumed = false

        csvText.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("~")) return@forEachIndexed

            if (!headerRowConsumed) {
                headerRowConsumed = true
                return@forEachIndexed
            }

            val fields = splitCsvLine(line)
            if (fields.size < 8) {
                warnings += "Line ${index + 1}: expected 8 columns, got ${fields.size} - skipped"
                return@forEachIndexed
            }

            val name = fields[0].trim()
            val shortName = fields[1].trim()
            val modeAndPid = normalizeHex(fields[2])
            val equationRaw = fields[3].trim()
            val minValue = fields[4].trim().toDoubleOrNull()
            val maxValue = fields[5].trim().toDoubleOrNull()
            val unit = fields[6].trim()
            val header = normalizeHex(fields[7])

            if (name.isEmpty() || equationRaw.isEmpty()) {
                warnings += "Line ${index + 1}: missing name or equation - skipped"
                return@forEachIndexed
            }

            val formula = try {
                Formula.parse(equationRaw)
            } catch (e: Exception) {
                warnings += "Line ${index + 1} ('$name'): formula error - ${e.message} - skipped"
                return@forEachIndexed
            }

            rows += PidRow(name, shortName, modeAndPid, header, formula, minValue, maxValue, unit)
        }

        return ParseResult(rows, warnings)
    }

    private fun normalizeHex(field: String): String? =
        field.trim().removePrefix("0x").removePrefix("0X").uppercase().ifEmpty { null }

    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> current.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { fields += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }
}
