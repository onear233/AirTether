package com.bthotspot.android.ble

import com.bthotspot.android.protocol.BleProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ServiceStateHolder {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _connectedCount = MutableStateFlow(0)
    val connectedCount: StateFlow<Int> = _connectedCount.asStateFlow()

    private val _status = MutableStateFlow(BleProtocol.HotspotStatus())
    val status: StateFlow<BleProtocol.HotspotStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun setAdvertising(advertising: Boolean) {
        _isAdvertising.value = advertising
    }

    fun setConnectedCount(count: Int) {
        _connectedCount.value = count
    }

    fun setStatus(status: BleProtocol.HotspotStatus) {
        _status.value = status
    }

    fun addLog(message: String) {
        val line = "[${timeFormat.format(Date())}] $message"
        val current = _logs.value
        _logs.value = (listOf(line) + current).take(50)
    }
}
