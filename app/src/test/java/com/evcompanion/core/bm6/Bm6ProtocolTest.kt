package com.evcompanion.core.bm6

import com.evcompanion.core.bm6.Bm6Protocol.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher

class Bm6ProtocolTest {

    private fun packet(plainHex: String) = Bm6Protocol.crypt(Cipher.ENCRYPT_MODE, plainHex.hexToBytes())

    @Test
    fun `decodes voltage soc and temperature from a live reading`() {
        // temp sign 00, temp 0x14=20C, soc 0x5a=90%, voltage chars 15..17 = 0x4ec = 1260 -> 12.60V
        val reading = Bm6Protocol.parse(packet("d1550700140b5a04ec00000000000000"))
        assertEquals(Bm6Protocol.Reading(voltage = 12.60, socPercent = 90.0, temperatureC = 20.0), reading)
    }

    @Test
    fun `sign byte 01 makes temperature negative`() {
        val reading = Bm6Protocol.parse(packet("d1550701050b5a04ec00000000000000"))
        assertEquals(-5.0, reading?.temperatureC)
    }

    @Test
    fun `ignores other packet types and partial blocks`() {
        assertNull(Bm6Protocol.parse(packet("d1550500000000000000000000000000")))
        assertNull(Bm6Protocol.parse(ByteArray(10)))
    }

    @Test
    fun `start command decrypts back to the known plaintext`() {
        val plain = Bm6Protocol.crypt(Cipher.DECRYPT_MODE, Bm6Protocol.startCommand)
        assertEquals("d1550700000000000000000000000000", plain.joinToString("") { "%02x".format(it) })
    }
}
