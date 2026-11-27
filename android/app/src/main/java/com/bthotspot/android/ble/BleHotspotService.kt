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
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bthotspot.android.MainActivity
import com.bthotspot.android.hotspot.HotspotController
import com.bthotspot.android.monitor.PhoneStateMonitor
import com.bthotspot.android.notification.AirTetherNotificationListener
import com.bthotspot.android.protocol.BleFrameCodec
import com.bthotspot.android.protocol.BleProtocol
import com.bthotspot.android.transfer.FileTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BleHotspotService : Service() {

    companion object {
        const val ACTION_START = "com.bthotspot.android.ACTION_START"
        const val ACTION_STOP = "com.bthotspot.android.ACTION_STOP"
        private const val CHANNEL_ID = "ble_hotspot_channel"
        private const val ALERT_CHANNEL_ID = "airtether_alerts_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var activeInstance: BleHotspotService? = null

        fun sendClipboardToPc(text: String, openInBrowser: Boolean = false): Boolean {
            val svc = activeInstance ?: return false
            val trimmed = text.trim().take(1200)
            if (trimmed.isBlank()) return false
            val json = JSONObject().apply {
                put("t", "clip")
                put("text", trimmed)
                put("open", openInBrowser)
            }.toString()
            svc.sendEventJsonToPc(json)
            ServiceStateHolder.addLog("已发送剪贴板到电脑 (${trimmed.length} 字符)")
            return true
        }

        fun sendFileToPc(uri: Uri): Boolean {
            val svc = activeInstance ?: return false
            svc.startOutgoingFileTransfer(uri)
            return true
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var hotspotController: HotspotController
    private lateinit var phoneStateMonitor: PhoneStateMonitor
    private lateinit var fileTransferManager: FileTransferManager

    private val frameCodec = BleFrameCodec()
    private val alertNotifId = AtomicInteger(2000)

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var callCharacteristic: BluetoothGattCharacteristic? = null
    private var eventUpCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedCallDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedEventDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val deviceMtu = ConcurrentHashMap<String, Int>()

    private var isAdvertisingStarted = false
    private var isServiceInitialized = false
    private var btReceiverRegistered = false
    private var lastNotifiedStatus: BleProtocol.HotspotStatus? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        ServiceStateHolder.addLog("检测到手机蓝牙已开启，自动恢复 BLE 服务")
                        stopBleServerAndAdvertising()
                        startBleServerAndAdvertising()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        ServiceStateHolder.addLog("手机蓝牙已关闭，暂停 BLE 服务")
                        stopBleServerAndAdvertising()
                        ServiceStateHolder.setAdvertising(false)
                        ServiceStateHolder.setConnectedCount(0)
                        updateNotification("手机蓝牙已关闭")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        hotspotController = HotspotController(this)
        phoneStateMonitor = PhoneStateMonitor(this, hotspotController)
        fileTransferManager = FileTransferManager(this, hotspotController)
        AirTetherNotificationListener.onMirrorJsonReady = { json ->
            sendEventJsonToPc(json)
        }
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

        if (!isServiceInitialized) {
            isServiceInitialized = true
            registerBluetoothStateReceiver()
            phoneStateMonitor.start()
            startBleServerAndAdvertising()
            observeStateUpdates()
        } else if (gattServer == null || !isAdvertisingStarted) {
            startBleServerAndAdvertising()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance === this) activeInstance = null
        AirTetherNotificationListener.onMirrorJsonReady = null
        fileTransferManager.stopServer()
        unregisterBluetoothStateReceiver()
        stopBleServerAndAdvertising()
        phoneStateMonitor.stop()
        hotspotController.unbind()
        isServiceInitialized = false
        lastNotifiedStatus = null
        ServiceStateHolder.setRunning(false)
        ServiceStateHolder.setAdvertising(false)
        ServiceStateHolder.setConnectedCount(0)
        ServiceStateHolder.addLog("BLE 后台服务已停止")
        serviceScope.cancel()
    }

    private fun registerBluetoothStateReceiver() {
        if (btReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bluetoothStateReceiver, filter)
        }
        btReceiverRegistered = true
    }

    private fun unregisterBluetoothStateReceiver() {
        if (!btReceiverRegistered) return
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        btReceiverRegistered = false
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
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE 热点控制服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持低功耗蓝牙广播与 GATT 连接以响应电脑端热点控制"
            }
            nm.createNotificationChannel(channel)

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "AirTether 协同提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "接收电脑剪贴板、网页接力与文件快传完成提醒"
            }
            nm.createNotificationChannel(alertChannel)
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
                if (status != lastNotifiedStatus) {
                    lastNotifiedStatus = status
                    notifyAllSubscribed(status)
                    refreshNotificationText()
                }
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
                delay(5000)
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

        if (gattServer != null) {
            startAdvertisingInternal()
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

        val eventUpChar = BluetoothGattCharacteristic(
            BleProtocol.EVENT_UP_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val eventUpCccd = BluetoothGattDescriptor(
            BleProtocol.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        eventUpChar.addDescriptor(eventUpCccd)
        eventUpCharacteristic = eventUpChar

        val eventDownChar = BluetoothGattCharacteristic(
            BleProtocol.EVENT_DOWN_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(statusChar)
        service.addCharacteristic(commandChar)
        service.addCharacteristic(callChar)
        service.addCharacteristic(eventUpChar)
        service.addCharacteristic(eventDownChar)
        server.addService(service)
        ServiceStateHolder.addLog("GATT Server 已启动")

        val adv = bluetoothAdapter?.bluetoothLeAdvertiser
        if (adv == null) {
            ServiceStateHolder.addLog("设备不支持 BLE 广播 (Peripheral Mode)")
            return
        }
        advertiser = adv
        startAdvertisingInternal()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal() {
        val adv = advertiser ?: bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        advertiser = adv
        if (bluetoothAdapter?.isEnabled != true) return

        if (isAdvertisingStarted) {
            runCatching { adv.stopAdvertising(advertiseCallback) }
            isAdvertisingStarted = false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
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

        runCatching {
            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
            isAdvertisingStarted = true
        }.onFailure {
            ServiceStateHolder.addLog("重启 BLE 广播异常: ${it.message}")
        }
    }

    private fun scheduleRestartAdvertising() {
        serviceScope.launch {
            delay(350)
            if (isServiceInitialized && bluetoothAdapter?.isEnabled == true) {
                startAdvertisingInternal()
            }
        }
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
        subscribedEventDevices.clear()
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
                subscribedEventDevices.remove(addr)
                deviceMtu.remove(addr)
                ServiceStateHolder.setConnectedCount(connectedDevices.size)
                ServiceStateHolder.addLog("电脑已断开: $name ($addr)，重新开启广播")
                refreshNotificationText()
                scheduleRestartAdvertising()
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
                val map = when (descriptor.characteristic?.uuid) {
                    BleProtocol.CALL_CHAR_UUID -> subscribedCallDevices
                    BleProtocol.EVENT_UP_CHAR_UUID -> subscribedEventDevices
                    else -> subscribedDevices
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
                val charUuid = descriptor.characteristic?.uuid
                val map = when (charUuid) {
                    BleProtocol.CALL_CHAR_UUID -> subscribedCallDevices
                    BleProtocol.EVENT_UP_CHAR_UUID -> subscribedEventDevices
                    else -> subscribedDevices
                }
                if (enable) {
                    map[device.address] = device
                    if (charUuid == BleProtocol.STATUS_CHAR_UUID) {
                        lastNotifiedStatus = null
                    }
                    val label = when (charUuid) {
                        BleProtocol.CALL_CHAR_UUID -> "来电通知"
                        BleProtocol.EVENT_UP_CHAR_UUID -> "互联事件通道"
                        else -> "状态通知"
                    }
                    ServiceStateHolder.addLog("电脑已订阅$label: ${device.address}")
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
                    when (charUuid) {
                        BleProtocol.CALL_CHAR_UUID -> {
                            val char = callCharacteristic
                            if (char != null && server != null) {
                                val payload = BleProtocol.encodeCallInfo(phoneStateMonitor.callInfo.value, maxPayload)
                                notifyDevice(server, device, char, payload)
                            }
                        }
                        BleProtocol.STATUS_CHAR_UUID -> {
                            val char = statusCharacteristic
                            if (char != null && server != null) {
                                val payload = BleProtocol.encodeStatus(phoneStateMonitor.status.value, maxPayload)
                                notifyDevice(server, device, char, payload)
                            }
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
            when (characteristic.uuid) {
                BleProtocol.COMMAND_CHAR_UUID -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                    val cmd = BleProtocol.Command.fromBytes(value)
                    if (cmd != null) {
                        handleRemoteCommand(cmd, device.address)
                    } else {
                        ServiceStateHolder.addLog("收到未知指令: ${value?.joinToString(",")}")
                    }
                }
                BleProtocol.EVENT_DOWN_CHAR_UUID -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                    val fullJson = frameCodec.feedChunk(value)
                    if (fullJson != null) {
                        handleIncomingEventJson(fullJson, device.address)
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                    }
                }
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

    @SuppressLint("MissingPermission")
    fun sendEventJsonToPc(json: String) {
        val server = gattServer ?: return
        val char = eventUpCharacteristic ?: return
        if (subscribedEventDevices.isEmpty()) return

        serviceScope.launch(Dispatchers.IO) {
            for ((address, device) in subscribedEventDevices) {
                val mtu = deviceMtu[address] ?: 23
                val maxChunk = (mtu - 3).coerceIn(20, 200)
                val chunks = frameCodec.encode(json, maxChunk)
                for (chunk in chunks) {
                    notifyDevice(server, device, char, chunk)
                    if (chunks.size > 1) delay(28)
                }
            }
        }
    }

    private fun startOutgoingFileTransfer(uri: Uri) {
        serviceScope.launch {
            val transfer = fileTransferManager.startOutgoingFile(uri)
            if (transfer == null) {
                ServiceStateHolder.addLog("启动文件快传服务失败")
                return@launch
            }
            phoneStateMonitor.refreshSnapshot()
            notifyAllSubscribed(phoneStateMonitor.status.value)
            sendEventJsonToPc(transfer.toJson())
        }
    }

    private fun handleIncomingEventJson(jsonStr: String, fromAddress: String) {
        serviceScope.launch {
            val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return@launch
            when (obj.optString("t")) {
                "clip" -> {
                    val text = obj.optString("text")
                    val open = obj.optBoolean("open", false)
                    if (text.isNotBlank()) {
                        runCatching {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("AirTether", text))
                        }
                        val isUrl = text.startsWith("http://", true) || text.startsWith("https://", true)
                        ServiceStateHolder.addLog("收到电脑剪贴板 ($fromAddress): ${text.take(40)}")
                        postClipboardNotification(text, isUrl && open)
                    }
                }
                "notif_dismiss" -> {
                    val key = obj.optString("key")
                    if (key.isNotBlank()) {
                        val ok = AirTetherNotificationListener.cancelNotificationByKey(key)
                        ServiceStateHolder.addLog(if (ok) "已远程清除手机通知" else "清除通知失败（请检查通知使用权）")
                    }
                }
                "xfer_done" -> {
                    fileTransferManager.onTransferCompletedByPc()
                    phoneStateMonitor.refreshSnapshot()
                    notifyAllSubscribed(phoneStateMonitor.status.value)
                }
                "xfer_pull_start" -> {
                    val ip = obj.optString("ip")
                    val port = obj.optInt("port", 0)
                    val token = obj.optString("token")
                    val name = obj.optString("name", "file.bin")
                    if (ip.isNotBlank() && port > 0 && token.isNotBlank()) {
                        val (ok, info) = fileTransferManager.downloadIncomingFile(ip, port, token, name)
                        postTransferNotification(
                            if (ok) "已接收电脑文件" else "接收电脑文件失败",
                            if (ok) "$name → $info" else "$name ($info)"
                        )
                        val ack = JSONObject().apply {
                            put("t", "xfer_done")
                            put("ok", ok)
                            put("msg", info)
                        }.toString()
                        sendEventJsonToPc(ack)
                    }
                }
            }
        }
    }

    private fun postClipboardNotification(text: String, isUrl: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val openIntent = if (isUrl) {
            Intent(Intent.ACTION_VIEW, Uri.parse(text)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
        val pi = PendingIntent.getActivity(
            this,
            alertNotifId.incrementAndGet(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (isUrl) "收到电脑网页链接（点击打开）" else "已同步电脑剪贴板")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
            .setAutoCancel(true)
            .setContentIntent(pi)
        nm.notify(alertNotifId.incrementAndGet(), builder.build())
    }

    private fun postTransferNotification(title: String, body: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
        nm.notify(alertNotifId.incrementAndGet(), builder.build())
    }
}
