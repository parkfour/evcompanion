package com.evcompanion.core.preset.formula

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Validates the Torque-dialect formula engine against real rows from the bundled IONIQ 5 CSV
 * (see assets/presets/ioniq5_awd_77kwh.csv), with hand-computed expected outputs. This is the
 * main safety net for the formula dialect, which was reverse-engineered from the file rather
 * than from an official spec.
 */
class FormulaEvaluatorTest {

    private fun bytes(size: Int, vararg indexToValue: Pair<Int, Int>): ByteArray {
        val arr = ByteArray(size)
        for ((i, v) in indexToValue) arr[i] = v.toByte()
        return arr
    }

    @Test
    fun `simple division - cell voltage`() {
        // 000_Cell Voltage 001,C001,0x220102,e/50,2.70,4.30,V,7E4  -- 'e' = byte offset 4
        val formula = Formula.parse("e/50")
        val b = bytes(8, 4 to 200)
        assertEquals(4.0, formula.evaluate(bytes = b), 1e-9)
    }

    @Test
    fun `state of charge - af div 2`() {
        // 000_State of Charge Display,SOC,0x220105,af/2 -- 'af' = byte offset 31
        val formula = Formula.parse("af/2")
        val b = bytes(40, 31 to 84)
        assertEquals(42.0, formula.evaluate(bytes = b), 1e-9)
    }

    @Test
    fun `signed byte combine - battery current`() {
        // 000_Battery Current,BattA,0x220101,((Signed(K)*256)+L)/10 -- K=offset10, L=offset11
        val formula = Formula.parse("((Signed(K)*256)+L)/10")
        // K=0xFF (-1 signed), L=56 -> (-256+56)/10 = -20.0
        val b = bytes(16, 10 to 0xFF, 11 to 56)
        assertEquals(-20.0, formula.evaluate(bytes = b), 1e-9)
    }

    @Test
    fun `shift-left two-byte combine - battery dc voltage`() {
        // 000_Battery DC Voltage,BattV,0x220101,((m<8)+n)/10 -- m=offset12, n=offset13
        val formula = Formula.parse("((m<8)+n)/10")
        val b = bytes(16, 12 to 20, 13 to 100)
        assertEquals(522.0, formula.evaluate(bytes = b), 1e-9)
    }

    @Test
    fun `bit extraction - hv charging flag`() {
        // 000_HV_Charging,Charging,0x220101,{j:7} -- j=offset9, bit 7
        val formula = Formula.parse("{j:7}")
        assertEquals(1.0, formula.evaluate(bytes = bytes(16, 9 to 0b10000000)), 1e-9)
        assertEquals(0.0, formula.evaluate(bytes = bytes(16, 9 to 0b01111111)), 1e-9)
    }

    @Test
    fun `int24 three-byte combine - odometer`() {
        // 001_Odometer,Odo,0x22B002,Int24(g:h:i) -- g=offset6, h=offset7, i=offset8
        val formula = Formula.parse("Int24(g:h:i)")
        val b = bytes(12, 6 to 1, 7 to 2, 8 to 3)
        assertEquals(66051.0, formula.evaluate(bytes = b), 1e-9)
    }

    @Test
    fun `val ref - calculated row depends on another rows resolved value`() {
        // 004_CALC Average Cell Voltage,Avg Cell,0x220101,val{000_Battery DC Voltage}/192
        val formula = Formula.parse("val{000_Battery DC Voltage}/192")
        assertEquals(2.0, formula.evaluate(resolved = mapOf("000_Battery DC Voltage" to 384.0)), 1e-9)
        assertEquals(setOf("000_Battery DC Voltage"), formula.referencedPidNames)
        assertEquals(false, formula.usesRawBytes)
    }

    @Test(expected = Formula.UnresolvedReferenceException::class)
    fun `val ref - throws when dependency is not yet resolved`() {
        val formula = Formula.parse("val{000_Battery DC Voltage}/192")
        formula.evaluate(resolved = emptyMap())
    }

    @Test
    fun `max with colon separated args - clamps to zero`() {
        // 004_CALC Estimated time 50kW 80% Charge: (MAX(0:(0.8-(val{...SOC}/100))))*92
        val formula = Formula.parse("(MAX(0:(0.8-(val{000_State of Charge Display}/100))))*92")
        assertEquals(18.4, formula.evaluate(resolved = mapOf("000_State of Charge Display" to 60.0)), 1e-9)
        assertEquals(0.0, formula.evaluate(resolved = mapOf("000_State of Charge Display" to 90.0)), 1e-9)
    }

    @Test
    fun `byte letter to index decoding matches spreadsheet columns`() {
        assertEquals(0, byteLettersToIndex("a"))
        assertEquals(25, byteLettersToIndex("z"))
        assertEquals(26, byteLettersToIndex("aa"))
        assertEquals(51, byteLettersToIndex("az"))
        assertEquals(52, byteLettersToIndex("ba"))
    }

    @Test
    fun `usesRawBytes is true for byte-var and bit-extract formulas`() {
        assertEquals(true, Formula.parse("e/50").usesRawBytes)
        assertEquals(true, Formula.parse("{j:7}").usesRawBytes)
        assertEquals(false, Formula.parse("val{x}/2").usesRawBytes)
    }

    @Test
    fun `requested charge power - pack volts times requested amps`() {
        // 000_Requested Charge Power,0x220101,(((m<8)+n)/10*((h<8)+i)/10)/1000
        // volts = bytes m,n (offsets 12,13 - same as 000_Battery DC Voltage), amps = h,i (7,8)
        val formula = Formula.parse("(((m<8)+n)/10*((h<8)+i)/10)/1000")
        // 0x1D1A = 7450 -> 745.0 V; 0x05DC = 1500 -> 150.0 A; 745 * 150 / 1000 = 111.75 kW
        val b = bytes(16, 12 to 0x1D, 13 to 0x1A, 7 to 0x05, 8 to 0xDC, 4 to 140)
        assertEquals(111.75, formula.evaluate(bytes = b), 1e-9)
    }
}
