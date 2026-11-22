package com.bthotspot.android.protocol

import java.util.UUID

object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("A0B10001-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
    val STATUS_CHAR_UUID: UUID = UUID.fromString("A0B10002-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
    val COMMAND_CHAR_UUID: UUID = UUID.fromString("A0B10003-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
    val CALL_CHAR_UUID: UUID = UUID.fromString("A0B10004-5C7E-4A3B-9E1D-7F8A9B0C1D2E")
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
        val ssid: String = ""
    )

    /**
     * Encodes [HotspotStatus] into a compact binary payload:
     * [0] version (0x01)
     * [1] hotspotState (0 = OFF, 1 = ON, 2 = TRANSITIONING)
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

        val result = ByteArray(headerSize + ssidLen)
        result[0] = PROTOCOL_VERSION
        result[1] = when {
            status.isTransitioning -> 2
            status.hotspotEnabled -> 1
            else -> 0
        }.toByte()
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
