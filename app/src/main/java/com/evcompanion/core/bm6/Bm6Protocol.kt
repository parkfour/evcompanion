package com.evcompanion.core.bm6

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BM6 12V battery monitor (sold as ANCEL BM200 etc.) BLE protocol, per the community
 * reverse-engineering in github.com/JeffWDH/bm6-battery-monitor: every packet is AES-128-CBC
 * with a fixed key and zero IV. Write the encrypted start command to FFF3 and the monitor then
 * streams encrypted readings on FFF4 notifications.
 */
object Bm6Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val WRITE_UUID: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
    val NOTIFY_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")

    private val KEY = intArrayOf(108, 101, 97, 103, 101, 110, 100, 255, 254, 48, 49, 48, 48, 48, 48, 57)
        .map { it.toByte() }.toByteArray()
    private const val READING_PREFIX = "d15507"
    private const val START_COMMAND_HEX = "d1550700000000000000000000000000"

    data class Reading(val voltage: Double, val socPercent: Double, val temperatureC: Double)

    val startCommand: ByteArray get() = crypt(Cipher.ENCRYPT_MODE, START_COMMAND_HEX.hexToBytes())

    /** Null for anything that isn't a whole-block live-reading packet. */
    fun parse(packet: ByteArray): Reading? {
        if (packet.isEmpty() || packet.size % 16 != 0) return null
        val hex = crypt(Cipher.DECRYPT_MODE, packet).joinToString("") { "%02x".format(it) }
        if (!hex.startsWith(READING_PREFIX)) return null
        // Offsets are hex-character positions, matching the reference implementation exactly
        // (voltage is the 12 bits at chars 15..17, i.e. it straddles a byte boundary).
        val temp = hex.substring(8, 10).toInt(16).toDouble()
        return Reading(
            voltage = hex.substring(15, 18).toInt(16) / 100.0,
            socPercent = hex.substring(12, 14).toInt(16).toDouble(),
            temperatureC = if (hex.substring(6, 8) == "01") -temp else temp,
        )
    }

    internal fun crypt(mode: Int, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(mode, SecretKeySpec(KEY, "AES"), IvParameterSpec(ByteArray(16)))
            doFinal(data)
        }

    internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
