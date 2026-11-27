package com.bthotspot.android.protocol

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("A0B10001-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
    val STATUS_CHAR_UUID: UUID = UUID.fromString("A0B10002-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
    val COMMAND_CHAR_UUID: UUID = UUID.fromString("A0B10003-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
    val CALL_CHAR_UUID: UUID = UUID.fromString("A0B10004-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
    val EVENT_UP_CHAR_UUID: UUID = UUID.fromString("A0B10005-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
    val EVENT_DOWN_CHAR_UUID: UUID = UUID.fromString("A0B10006-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PROTOCOL_VERSION: Byte = 0x01

    enum class NetworkType(val code: Byte, val label: String) {
        NONE(0, "无服务"),
        NET_2G(1, "2G"),
        NET_3G(2, "3G"),
        NET_4G(3, "4G"),
        NET_5G(4, "5G");

        companion object {
            fun fromCode(code: Byte): NetworkType = entries.firstOrNull { it.code == code } ?: NONE
        }
    }

    enum class Command(val code: Byte) {
        HOTSPOT_OFF(0x00),
        HOTSPOT_ON(0x01),
        HOTSPOT_TOGGLE(0x02),
        REFRESH_STATUS(0x03),
        CALL_REJECT(0x04),
        CALL_SILENCE(0x05);

        companion object {
            fun fromBytes(bytes: ByteArray?): Command? {
                if (bytes == null || bytes.isEmpty()) return null
                return entries.firstOrNull { it.code == bytes[0] }
            }
        }
    }

    enum class CallState(val code: Byte) {
        IDLE(0),
        RINGING(1),
        OFFHOOK(2)
    }

    data class CallInfo(
        val state: CallState = CallState.IDLE,
        val phoneNumber: String = "",
        val contactName: String = ""
    )

    data class HotspotStatus(
        val hotspotEnabled: Boolean = false,
        val isTransitioning: Boolean = false,
        val signalBars: Int = 0,
        val networkType: NetworkType = NetworkType.NONE,
        val batteryPercent: Int = -1,
        val ssid: String = "",
        val isCharging: Boolean = false
    )

    /**
     * Encodes [HotspotStatus] into a compact binary payload:
     * [0] version (0x01)
     * [1] flags: bits 0..1 = hotspotState (0 = OFF, 1 = ON, 2 = TRANSITIONING), bit 2 (0x04) = isCharging
     * [2] signalBars (0..4)
     * [3] networkType (0..4)
     * [4] batteryPercent (0..100, or 255 if unknown)
     * [5] ssidLength (0..maxSsidBytes)
     * [6..] UTF-8 SSID bytes
     */
    fun encodeStatus(status: HotspotStatus, maxPayloadBytes: Int = 512): ByteArray {
        val headerSize = 6
        val maxSsidBytes = (maxPayloadBytes - headerSize).coerceIn(0, 32)
        val rawSsid = status.ssid.toByteArray(Charsets.UTF_8)
        val ssidLen = rawSsid.size.coerceAtMost(maxSsidBytes)

        val hsBits = when {
            status.isTransitioning -> 2
            status.hotspotEnabled -> 1
            else -> 0
        }
        val chargingBit = if (status.isCharging) 0x04 else 0

        val result = ByteArray(headerSize + ssidLen)
        result[0] = PROTOCOL_VERSION
        result[1] = (hsBits or chargingBit).toByte()
        result[2] = status.signalBars.coerceIn(0, 4).toByte()
        result[3] = status.networkType.code
        result[4] = if (status.batteryPercent in 0..100) status.batteryPercent.toByte() else 0xFF.toByte()
        result[5] = ssidLen.toByte()
        if (ssidLen > 0) {
            System.arraycopy(rawSsid, 0, result, headerSize, ssidLen)
        }
        return result
    }

    /**
     * Encodes [CallInfo] into a compact binary payload:
     * [0] version (0x01)
     * [1] callState (0 = IDLE, 1 = RINGING, 2 = OFFHOOK)
     * [2] numberLen (0..32)
     * [3..3+numberLen-1] UTF-8 phoneNumber
     * [3+numberLen] nameLen (0..32)
     * [4+numberLen..] UTF-8 contactName
     */
    fun encodeCallInfo(info: CallInfo, maxPayloadBytes: Int = 512): ByteArray {
        val rawNum = info.phoneNumber.toByteArray(Charsets.UTF_8)
        val maxNum = (maxPayloadBytes - 4).coerceIn(0, 32)
        val numLen = rawNum.size.coerceAtMost(maxNum)

        val rawName = info.contactName.toByteArray(Charsets.UTF_8)
        val maxName = (maxPayloadBytes - 4 - numLen).coerceIn(0, 32)
        val nameLen = rawName.size.coerceAtMost(maxName)

        val result = ByteArray(4 + numLen + nameLen)
        result[0] = PROTOCOL_VERSION
        result[1] = info.state.code
        result[2] = numLen.toByte()
        if (numLen > 0) {
            System.arraycopy(rawNum, 0, result, 3, numLen)
        }
        result[3 + numLen] = nameLen.toByte()
        if (nameLen > 0) {
            System.arraycopy(rawName, 0, result, 4 + numLen, nameLen)
        }
        return result
    }
}

/**
 * Simple BLE chunked frame codec for UTF-8 JSON payloads over EVENT_UP / EVENT_DOWN characteristics.
 * Header per packet (3 bytes): [0]=msgId (1..255), [1]=chunkIndex, [2]=totalChunks.
 */
class BleFrameCodec {
    private val nextMsgId = AtomicInteger(1)

    private class PendingMessage(
        val totalChunks: Int,
        val createdAtMs: Long = System.currentTimeMillis()
    ) {
        val parts = arrayOfNulls<ByteArray>(totalChunks)
        var receivedCount = 0
    }

    private val pending = ConcurrentHashMap<Int, PendingMessage>()

    fun encode(json: String, maxChunkSize: Int = 20): List<ByteArray> {
        val payload = json.toByteArray(Charsets.UTF_8)
        val dataPerChunk = (maxChunkSize - 3).coerceIn(17, 240)
        val totalChunks = ((payload.size + dataPerChunk - 1) / dataPerChunk).coerceAtLeast(1)
        if (totalChunks > 64) return emptyList()

        val msgId = ((nextMsgId.getAndIncrement() and 0xFF).takeIf { it != 0 } ?: 1).toByte()
        val chunks = ArrayList<ByteArray>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * dataPerChunk
            val len = (payload.size - start).coerceAtMost(dataPerChunk).coerceAtLeast(0)
            val pkt = ByteArray(3 + len)
            pkt[0] = msgId
            pkt[1] = i.toByte()
            pkt[2] = totalChunks.toByte()
            if (len > 0) {
                System.arraycopy(payload, start, pkt, 3, len)
            }
            chunks.add(pkt)
        }
        return chunks
    }

    fun feedChunk(packet: ByteArray?): String? {
        if (packet == null || packet.size < 3) return null
        val msgId = packet[0].toInt() and 0xFF
        val index = packet[1].toInt() and 0xFF
        val total = packet[2].toInt() and 0xFF
        if (total <= 0 || total > 64 || index >= total) return null

        val data = packet.copyOfRange(3, packet.size)
        if (total == 1) {
            return String(data, Charsets.UTF_8)
        }

        // Purge stale partial messages (>15s)
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.createdAtMs > 15_000 }

        val entry = pending.computeIfAbsent(msgId) { PendingMessage(total) }
        synchronized(entry) {
            if (entry.totalChunks != total) {
                pending.remove(msgId)
                return null
            }
            if (entry.parts[index] == null) {
                entry.parts[index] = data
                entry.receivedCount++
            }
            if (entry.receivedCount == total) {
                pending.remove(msgId)
                val totalBytes = entry.parts.sumOf { it?.size ?: 0 }
                val merged = ByteArray(totalBytes)
                var offset = 0
                for (part in entry.parts) {
                    if (part != null) {
                        System.arraycopy(part, 0, merged, offset, part.size)
                        offset += part.size
                    }
                }
                return String(merged, Charsets.UTF_8)
            }
        }
        return null
    }
}
