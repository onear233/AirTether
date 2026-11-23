package com.bthotspot.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bthotspot.android.MainActivity
import com.bthotspot.android.hotspot.HotspotController
import com.bthotspot.android.monitor.PhoneStateMonitor
import com.bthotspot.android.protocol.BleProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class BleHotspotService : Service() {

    companion object {
        const val ACTION_START = "com.bthotspot.android.ACTION_START"
        const val ACTION_STOP = "com.bthotspot.android.ACTION_STOP"
        private const val CHANNEL_ID = "ble_hotspot_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var hotspotController: HotspotController
    private lateinit var phoneStateMonitor: PhoneStateMonitor

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var callCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedCallDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val deviceMtu = ConcurrentHashMap<String, Int>()

    private var isAdvertisingStarted = false

    override fun onCreate() {
        super.onCreate()
        hotspotController = HotspotController(this)
        phoneStateMonitor = PhoneStateMonitor(this, hotspotController)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceStateHolder.addLog("收到停止服务指令")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification("正在初始化 BLE 服务...")
        ServiceStateHolder.setRunning(true)

        if (!hasBlePermissions()) {
            ServiceStateHolder.addLog("缺少蓝牙权限，无法启动 BLE 服务")
            updateNotification("缺少蓝牙权限，请在应用内授权")
            return START_STICKY
        }

        phoneStateMonitor.start()
        startBleServerAndAdvertising()
        observeStateUpdates()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopBleServerAndAdvertising()
        phoneStateMonitor.stop()
        hotspotController.unbind()
        ServiceStateHolder.setRunning(false)
        ServiceStateHolder.setAdvertising(false)
        ServiceStateHolder.setConnectedCount(0)
        ServiceStateHolder.addLog("BLE 后台服务已停止")
        serviceScope.cancel()
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE 热点控制服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持低功耗蓝牙广播与 GATT 连接以响应电脑端热点控制"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BleHotspotService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("AirTether 服务运行中")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(0, "停止服务", stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun observeStateUpdates() {
        serviceScope.launch {
            hotspotController.refreshAutoSsid()
            phoneStateMonitor.refreshSnapshot()
        }

        serviceScope.launch {
            phoneStateMonitor.status.collect { status ->
                ServiceStateHolder.setStatus(status)
                notifyAllSubscribed(status)
                refreshNotificationText()
            }
        }

        serviceScope.launch {
            phoneStateMonitor.callInfo.collect { callInfo ->
                if (callInfo.state == BleProtocol.CallState.RINGING) {
                    ServiceStateHolder.addLog("来电: ${callInfo.phoneNumber}")
                }
                notifyAllCallSubscribed(callInfo)
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(3000)
                phoneStateMonitor.refreshSnapshot()
            }
        }
    }

    private fun refreshNotificationText() {
        val count = connectedDevices.size
        val st = phoneStateMonitor.status.value
        val hsText = if (st.hotspotEnabled) "热点已开启" else "热点已关闭"
        val connText = if (count > 0) "已连接 $count 台电脑" else "等待电脑连接"
        updateNotification("$connText · $hsText · ${st.networkType.label}")
    }

    @SuppressLint("MissingPermission")
    private fun startBleServerAndAdvertising() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled != true) {
            ServiceStateHolder.addLog("蓝牙未开启，请先打开手机蓝牙")
            updateNotification("蓝牙未开启")
            return
        }

        val server = bluetoothManager?.openGattServer(this, gattServerCallback)
        if (server == null) {
            ServiceStateHolder.addLog("无法打开 BluetoothGattServer")
            return
        }
        gattServer = server

        val service = BluetoothGattService(
            BleProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val statusChar = BluetoothGattCharacteristic(
            BleProtocol.STATUS_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            BleProtocol.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        statusChar.addDescriptor(cccd)
        statusCharacteristic = statusChar

        val commandChar = BluetoothGattCharacteristic(
            BleProtocol.COMMAND_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val callChar = BluetoothGattCharacteristic(
            BleProtocol.CALL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val callCccd = BluetoothGattDescriptor(
            BleProtocol.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        callChar.addDescriptor(callCccd)
        callCharacteristic = callChar

        service.addCharacteristic(statusChar)
        service.addCharacteristic(commandChar)
        service.addCharacteristic(callChar)
        server.addService(service)
        ServiceStateHolder.addLog("GATT Server 已启动")

        val adv = bluetoothAdapter?.bluetoothLeAdvertiser
        if (adv == null) {
            ServiceStateHolder.addLog("设备不支持 BLE 广播 (Peripheral Mode)")
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
        isAdvertisingStarted = true
    }

    @SuppressLint("MissingPermission")
    private fun stopBleServerAndAdvertising() {
        if (isAdvertisingStarted) {
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            isAdvertisingStarted = false
        }
        runCatching { gattServer?.close() }
        gattServer = null
        connectedDevices.clear()
        subscribedDevices.clear()
        subscribedCallDevices.clear()
        deviceMtu.clear()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            ServiceStateHolder.setAdvertising(true)
            ServiceStateHolder.addLog("BLE 广播已开启，等待电脑扫描连接")
            refreshNotificationText()
        }

        override fun onStartFailure(errorCode: Int) {
            ServiceStateHolder.setAdvertising(false)
            ServiceStateHolder.addLog("BLE 广播启动失败: errorCode=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyAllSubscribed(status: BleProtocol.HotspotStatus) {
        val server = gattServer ?: return
        val char = statusCharacteristic ?: return
        if (subscribedDevices.isEmpty()) return

        for ((address, device) in subscribedDevices) {
            val mtu = deviceMtu[address] ?: 23
            val maxPayload = (mtu - 3).coerceAtLeast(20)
            val payload = BleProtocol.encodeStatus(status, maxPayload)
            notifyDevice(server, device, char, payload)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyAllCallSubscribed(callInfo: BleProtocol.CallInfo) {
        val server = gattServer ?: return
        val char = callCharacteristic ?: return
        if (subscribedCallDevices.isEmpty()) return

        for ((address, device) in subscribedCallDevices) {
            val mtu = deviceMtu[address] ?: 23
            val maxPayload = (mtu - 3).coerceAtLeast(20)
            val payload = BleProtocol.encodeCallInfo(callInfo, maxPayload)
            notifyDevice(server, device, char, payload)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyDevice(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, payload)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            val name = runCatching { device.name }.getOrNull() ?: addr
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices[addr] = device
                ServiceStateHolder.setConnectedCount(connectedDevices.size)
                ServiceStateHolder.addLog("电脑已连接: $name ($addr)")
                refreshNotificationText()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(addr)
                subscribedDevices.remove(addr)
                subscribedCallDevices.remove(addr)
                deviceMtu.remove(addr)
                ServiceStateHolder.setConnectedCount(connectedDevices.size)
                ServiceStateHolder.addLog("电脑已断开: $name ($addr)")
                refreshNotificationText()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            deviceMtu[device.address] = mtu
            ServiceStateHolder.addLog("MTU 协商完成 (${device.address}): $mtu")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                BleProtocol.STATUS_CHAR_UUID -> {
                    val mtu = deviceMtu[device.address] ?: 512
                    val full = BleProtocol.encodeStatus(phoneStateMonitor.status.value, mtu.coerceAtLeast(64))
                    val slice = if (offset >= full.size) ByteArray(0) else full.copyOfRange(offset, full.size)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                }
                BleProtocol.CALL_CHAR_UUID -> {
                    val mtu = deviceMtu[device.address] ?: 512
                    val full = BleProtocol.encodeCallInfo(phoneStateMonitor.callInfo.value, mtu.coerceAtLeast(64))
                    val slice = if (offset >= full.size) ByteArray(0) else full.copyOfRange(offset, full.size)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == BleProtocol.CCCD_UUID) {
                val map = if (descriptor.characteristic?.uuid == BleProtocol.CALL_CHAR_UUID) {
                    subscribedCallDevices
                } else {
                    subscribedDevices
                }
                val value = if (map.containsKey(device.address)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == BleProtocol.CCCD_UUID) {
                val enable = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                val isCallChar = descriptor.characteristic?.uuid == BleProtocol.CALL_CHAR_UUID
                val map = if (isCallChar) subscribedCallDevices else subscribedDevices
                if (enable) {
                    map[device.address] = device
                    ServiceStateHolder.addLog(
                        if (isCallChar) "电脑已订阅来电通知: ${device.address}" else "电脑已订阅状态通知: ${device.address}"
                    )
                } else {
                    map.remove(device.address)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                if (enable) {
                    val server = gattServer
                    val mtu = deviceMtu[device.address] ?: 23
                    val maxPayload = (mtu - 3).coerceAtLeast(20)
                    if (isCallChar) {
                        val char = callCharacteristic
                        if (char != null && server != null) {
                            val payload = BleProtocol.encodeCallInfo(phoneStateMonitor.callInfo.value, maxPayload)
                            notifyDevice(server, device, char, payload)
                        }
                    } else {
                        val char = statusCharacteristic
                        if (char != null && server != null) {
                            val payload = BleProtocol.encodeStatus(phoneStateMonitor.status.value, maxPayload)
                            notifyDevice(server, device, char, payload)
                        }
                    }
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid == BleProtocol.COMMAND_CHAR_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                val cmd = BleProtocol.Command.fromBytes(value)
                if (cmd != null) {
                    handleRemoteCommand(cmd, device.address)
                } else {
                    ServiceStateHolder.addLog("收到未知指令: ${value?.joinToString(",")}")
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }
    }

    private fun handleRemoteCommand(cmd: BleProtocol.Command, fromAddress: String) {
        serviceScope.launch {
            when (cmd) {
                BleProtocol.Command.REFRESH_STATUS -> {
                    ServiceStateHolder.addLog("收到刷新状态请求 ($fromAddress)")
                    phoneStateMonitor.refreshSnapshot()
                    notifyAllSubscribed(phoneStateMonitor.status.value)
                }
                BleProtocol.Command.HOTSPOT_ON,
                BleProtocol.Command.HOTSPOT_OFF,
                BleProtocol.Command.HOTSPOT_TOGGLE -> {
                    val currentOn = phoneStateMonitor.status.value.hotspotEnabled
                    val targetOn = when (cmd) {
                        BleProtocol.Command.HOTSPOT_ON -> true
                        BleProtocol.Command.HOTSPOT_OFF -> false
                        BleProtocol.Command.HOTSPOT_TOGGLE -> !currentOn
                        else -> currentOn
                    }
                    val actionLabel = if (targetOn) "开启" else "关闭"
                    ServiceStateHolder.addLog("收到远程指令: ${actionLabel}热点 ($fromAddress)")
                    val result = hotspotController.setHotspotEnabled(targetOn)
                    if (result.success) {
                        ServiceStateHolder.addLog("热点${actionLabel}成功")
                        if (targetOn) {
                            hotspotController.refreshAutoSsid()
                        }
                    } else {
                        ServiceStateHolder.addLog("热点${actionLabel}失败: ${result.error}")
                    }
                    delay(400)
                    phoneStateMonitor.refreshSnapshot()
                    notifyAllSubscribed(phoneStateMonitor.status.value)
                }
                BleProtocol.Command.CALL_REJECT -> {
                    ServiceStateHolder.addLog("收到远程指令: 拒接来电 ($fromAddress)")
                    val res = hotspotController.rejectIncomingCall()
                    ServiceStateHolder.addLog(if (res.success) "已拒接来电" else "拒接失败: ${res.error}")
                }
                BleProtocol.Command.CALL_SILENCE -> {
                    ServiceStateHolder.addLog("收到远程指令: 来电静音 ($fromAddress)")
                    val res = hotspotController.silenceIncomingCall()
                    ServiceStateHolder.addLog(if (res.success) "已静音来电铃声" else "静音失败: ${res.error}")
                }
            }
        }
    }
}
