package com.bthotspot.android.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.bthotspot.android.hotspot.HotspotController
import com.bthotspot.android.protocol.BleProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PhoneStateMonitor(
    private val context: Context,
    private val hotspotController: HotspotController
) {
    private val appContext = context.applicationContext
    private val telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _status = MutableStateFlow(BleProtocol.HotspotStatus())
    val status: StateFlow<BleProtocol.HotspotStatus> = _status.asStateFlow()

    private val _callInfo = MutableStateFlow(BleProtocol.CallInfo())
    val callInfo: StateFlow<BleProtocol.CallInfo> = _callInfo.asStateFlow()

    private var isStarted = false
    private var inService = true
    private var lastSignalBars = 0
    private var lastNetworkType = BleProtocol.NetworkType.NONE
    private var lastBattery = -1
    private var lastIsCharging = false
    private var lastHotspotOn = false
    private var lastHotspotTransition = false
    private var lastSsid = ""

    private var telephonyCallback: Any? = null
    private var lastDisplayInfo: TelephonyDisplayInfo? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                    val state = intent.getIntExtra("wifi_state", -1)
                    when (state) {
                        13 -> { // ENABLED
                            lastHotspotOn = true
                            lastHotspotTransition = false
                        }
                        11 -> { // DISABLED
                            lastHotspotOn = false
                            lastHotspotTransition = false
                        }
                        12 -> { // ENABLING
                            lastHotspotOn = true
                            lastHotspotTransition = true
                        }
                        10 -> { // DISABLING
                            lastHotspotOn = false
                            lastHotspotTransition = true
                        }
                    }
                    lastSsid = hotspotController.getHotspotSsid()
                    emitStatus()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    lastIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    if (level >= 0 && scale > 0) {
                        lastBattery = ((level * 100f) / scale).toInt().coerceIn(0, 100)
                        emitStatus()
                    }
                }
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    @Suppress("DEPRECATION")
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    when (stateStr) {
                        TelephonyManager.EXTRA_STATE_RINGING -> {
                            val resolvedNumber = when {
                                !number.isNullOrBlank() -> number
                                _callInfo.value.state == BleProtocol.CallState.RINGING &&
                                    _callInfo.value.phoneNumber.isNotBlank() -> _callInfo.value.phoneNumber
                                else -> "未知号码"
                            }
                            _callInfo.value = BleProtocol.CallInfo(
                                state = BleProtocol.CallState.RINGING,
                                phoneNumber = resolvedNumber
                            )
                        }
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                            _callInfo.value = _callInfo.value.copy(
                                state = BleProtocol.CallState.OFFHOOK
                            )
                        }
                        TelephonyManager.EXTRA_STATE_IDLE -> {
                            _callInfo.value = BleProtocol.CallInfo(
                                state = BleProtocol.CallState.IDLE,
                                phoneNumber = ""
                            )
                        }
                    }
                }
            }
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true

        val filter = IntentFilter().apply {
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(broadcastReceiver, filter)
        }

        registerTelephonyListener()
        refreshSnapshot()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false

        runCatching { appContext.unregisterReceiver(broadcastReceiver) }
        unregisterTelephonyListener()
    }

    fun refreshSnapshot() {
        val (hsOn, hsTrans) = hotspotController.getHotspotState()
        lastHotspotOn = hsOn
        lastHotspotTransition = hsTrans
        lastSsid = hotspotController.getHotspotSsid()

        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            lastIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                lastBattery = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            }
        }

        if (hasPhonePermission()) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telephonyManager.signalStrength?.let { ss ->
                        lastSignalBars = ss.level.coerceIn(0, 4)
                    }
                }
                val displayInfo = lastDisplayInfo
                lastNetworkType = if (displayInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    mapDisplayInfo(displayInfo)
                } else {
                    mapRawNetworkType(telephonyManager.dataNetworkType)
                }
            }
        }
        emitStatus()
    }

    private fun hasPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun registerTelephonyListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(),
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.DisplayInfoListener,
                TelephonyCallback.ServiceStateListener {

                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    lastSignalBars = signalStrength.level.coerceIn(0, 4)
                    emitStatus()
                }

                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    lastDisplayInfo = telephonyDisplayInfo
                    lastNetworkType = mapDisplayInfo(telephonyDisplayInfo)
                    emitStatus()
                }

                override fun onServiceStateChanged(serviceState: ServiceState) {
                    inService = serviceState.state == ServiceState.STATE_IN_SERVICE
                    if (!inService) {
                        lastSignalBars = 0
                        lastNetworkType = BleProtocol.NetworkType.NONE
                    }
                    emitStatus()
                }
            }
            telephonyCallback = cb
            runCatching {
                telephonyManager.registerTelephonyCallback(appContext.mainExecutor, cb)
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    if (signalStrength != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        lastSignalBars = signalStrength.level.coerceIn(0, 4)
                        emitStatus()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onServiceStateChanged(serviceState: ServiceState?) {
                    inService = serviceState?.state == ServiceState.STATE_IN_SERVICE
                    if (!inService) {
                        lastSignalBars = 0
                        lastNetworkType = BleProtocol.NetworkType.NONE
                    } else if (hasPhonePermission()) {
                        runCatching {
                            lastNetworkType = mapRawNetworkType(telephonyManager.dataNetworkType)
                        }
                    }
                    emitStatus()
                }

                @Deprecated("Deprecated in Java")
                override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                    lastNetworkType = mapRawNetworkType(networkType)
                    emitStatus()
                }
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            val events = PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                PhoneStateListener.LISTEN_SERVICE_STATE or
                PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
            @Suppress("DEPRECATION")
            runCatching { telephonyManager.listen(listener, events) }
        }
    }

    private fun unregisterTelephonyListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let { cb ->
                runCatching { telephonyManager.unregisterTelephonyCallback(cb) }
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { listener ->
                @Suppress("DEPRECATION")
                runCatching { telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
            phoneStateListener = null
        }
    }

    private fun mapDisplayInfo(info: TelephonyDisplayInfo): BleProtocol.NetworkType {
        if (!inService) return BleProtocol.NetworkType.NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (info.overrideNetworkType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> return BleProtocol.NetworkType.NET_5G
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> return BleProtocol.NetworkType.NET_4G
            }
        }
        return mapRawNetworkType(info.networkType)
    }

    @Suppress("DEPRECATION")
    private fun mapRawNetworkType(type: Int): BleProtocol.NetworkType {
        if (!inService) return BleProtocol.NetworkType.NONE
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> BleProtocol.NetworkType.NET_5G
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> BleProtocol.NetworkType.NET_4G
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> BleProtocol.NetworkType.NET_3G
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM -> BleProtocol.NetworkType.NET_2G
            else -> BleProtocol.NetworkType.NONE
        }
    }

    private fun emitStatus() {
        val effectiveBars = if (inService) lastSignalBars else 0
        val effectiveType = if (inService) lastNetworkType else BleProtocol.NetworkType.NONE
        _status.value = BleProtocol.HotspotStatus(
            hotspotEnabled = lastHotspotOn,
            isTransitioning = lastHotspotTransition,
            signalBars = effectiveBars,
            networkType = effectiveType,
            batteryPercent = lastBattery,
            ssid = lastSsid,
            isCharging = lastIsCharging
        )
    }
}
