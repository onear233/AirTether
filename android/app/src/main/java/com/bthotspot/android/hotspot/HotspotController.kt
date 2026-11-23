package com.bthotspot.android.hotspot

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

enum class HotspotMode(val key: String, val displayName: String) {
    AUTO("auto", "自动选择"),
    ROOT("root", "Root 模式"),
    SHIZUKU("shizuku", "Shizuku 模式");

    companion object {
        fun fromKey(key: String?): HotspotMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

class HotspotController(private val context: Context) {

    private val prefs = context.getSharedPreferences("bt_hotspot_prefs", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    @Volatile
    private var shizukuService: IHotspotUserService? = null
    @Volatile
    private var pendingServiceDeferred: CompletableDeferred<IHotspotUserService?>? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShizukuHotspotUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("hotspot_service")
            .debuggable(false)
            .version(1)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val stub = service?.let { IHotspotUserService.Stub.asInterface(it) }
            shizukuService = stub
            pendingServiceDeferred?.complete(stub)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
    }

    var mode: HotspotMode
        get() = HotspotMode.fromKey(prefs.getString("hotspot_mode", HotspotMode.AUTO.key))
        set(value) = prefs.edit().putString("hotspot_mode", value.key).apply()

    var manualSsid: String
        get() = prefs.getString("manual_ssid", "")?.trim() ?: ""
        set(value) = prefs.edit().putString("manual_ssid", value.trim()).apply()

    var cachedAutoSsid: String
        get() = prefs.getString("cached_auto_ssid", "")?.trim() ?: ""
        private set(value) = prefs.edit().putString("cached_auto_ssid", value.trim()).apply()

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext false
            }
            val output = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && output.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Reads current Wi-Fi AP state from WifiManager via reflection.
     * Returns pair(isEnabled, isTransitioning).
     */
    fun getHotspotState(): Pair<Boolean, Boolean> {
        return try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val stateMethod = wifiManager.javaClass.getMethod("getWifiApState")
            val state = stateMethod.invoke(wifiManager) as Int
            // 10 = DISABLING, 11 = DISABLED, 12 = ENABLING, 13 = ENABLED
            when (state) {
                13 -> true to false
                12, 10 -> (state == 12) to true
                else -> false to false
            }
        } catch (_: Throwable) {
            try {
                val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
                (method.invoke(wifiManager) as Boolean) to false
            } catch (_: Throwable) {
                false to false
            }
        }
    }

    fun getDirectHotspotSsid(): String {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val config = wifiManager.javaClass.getMethod("getSoftApConfiguration").invoke(wifiManager)
                if (config != null) {
                    val ssid = config.javaClass.getMethod("getSsid").invoke(config) as? String
                    if (!ssid.isNullOrBlank()) return ssid.trim('"')
                }
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            val config = wifiManager.javaClass.getMethod("getWifiApConfiguration").invoke(wifiManager)
            if (config != null) {
                val ssidField = config.javaClass.getField("SSID")
                val ssid = ssidField.get(config) as? String
                if (!ssid.isNullOrBlank()) return ssid.trim('"')
            }
        }
        return ""
    }

    /**
     * Returns the effective Hotspot SSID:
     * 1. Direct or privileged auto-detected SSID if available (unless overridden by manualSsid)
     * 2. Fallback to user-configured manualSsid
     */
    fun getHotspotSsid(): String {
        val direct = getDirectHotspotSsid()
        if (direct.isNotBlank()) {
            cachedAutoSsid = direct
        }
        val auto = direct.ifBlank { cachedAutoSsid }
        val manual = manualSsid
        return manual.ifBlank { auto }
    }

    /**
     * Attempts to read the hotspot SSID via direct API, Shizuku UserService, or Root config file.
     */
    suspend fun refreshAutoSsid(): String = withContext(Dispatchers.IO) {
        val direct = getDirectHotspotSsid()
        if (direct.isNotBlank()) {
            cachedAutoSsid = direct
            return@withContext direct
        }

        if (isShizukuGranted()) {
            runCatching {
                val svc = getOrBindShizukuService()
                val shizukuSsid = svc?.hotspotSsid?.trim() ?: ""
                if (shizukuSsid.isNotBlank()) {
                    cachedAutoSsid = shizukuSsid
                    return@withContext shizukuSsid
                }
            }
        }

        if (isRootAvailable()) {
            runCatching {
                val cmd = "cat /data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml 2>/dev/null || cat /data/misc/wifi/softap.conf 2>/dev/null"
                val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                if (proc.waitFor(4, TimeUnit.SECONDS)) {
                    val xml = proc.inputStream.bufferedReader().readText()
                    val m = Regex("""<string name="(?:SSID|WifiSsid)">([^<]+)</string>""").find(xml)
                    val rootSsid = m?.groupValues?.getOrNull(1)?.trim()?.trim('"') ?: ""
                    if (rootSsid.isNotBlank()) {
                        cachedAutoSsid = rootSsid
                        return@withContext rootSsid
                    }
                } else {
                    proc.destroyForcibly()
                }
            }
        }

        cachedAutoSsid
    }

    suspend fun setHotspotEnabled(enabled: Boolean): TetheringHelper.Result {
        return when (mode) {
            HotspotMode.SHIZUKU -> executeViaShizuku(enabled)
            HotspotMode.ROOT -> executeViaRoot(enabled)
            HotspotMode.AUTO -> {
                if (isShizukuGranted()) {
                    val res = executeViaShizuku(enabled)
                    if (res.success) return res
                }
                if (isRootAvailable()) {
                    return executeViaRoot(enabled)
                }
                if (!isShizukuAvailable()) {
                    TetheringHelper.Result(false, "未检测到 Root 权限且 Shizuku 服务未运行")
                } else {
                    TetheringHelper.Result(false, "请先授予 Shizuku 权限或 Root 权限")
                }
            }
        }
    }

    private suspend fun executeViaShizuku(enabled: Boolean): TetheringHelper.Result =
        withContext(Dispatchers.IO) {
            if (!isShizukuAvailable()) {
                return@withContext TetheringHelper.Result(false, "Shizuku 未运行")
            }
            if (!isShizukuGranted()) {
                return@withContext TetheringHelper.Result(false, "未授予 Shizuku 权限")
            }
            val service = getOrBindShizukuService()
                ?: return@withContext TetheringHelper.Result(false, "Shizuku UserService 绑定超时")

            try {
                val ok = service.setHotspotEnabled(enabled)
                if (ok) {
                    TetheringHelper.Result(true)
                } else {
                    val err = runCatching { service.lastError }.getOrNull()
                    TetheringHelper.Result(false, err?.ifBlank { "Shizuku 调用失败" } ?: "Shizuku 调用失败")
                }
            } catch (t: Throwable) {
                shizukuService = null
                TetheringHelper.Result(false, "Shizuku 通信异常: ${t.message}")
            }
        }

    private suspend fun getOrBindShizukuService(): IHotspotUserService? {
        shizukuService?.let { existing ->
            if (existing.asBinder().pingBinder()) return existing
            shizukuService = null
        }

        val deferred = CompletableDeferred<IHotspotUserService?>()
        pendingServiceDeferred = deferred
        withContext(Dispatchers.Main) {
            runCatching { Shizuku.bindUserService(userServiceArgs, serviceConnection) }
                .onFailure { deferred.complete(null) }
        }
        return withTimeoutOrNull(5000) { deferred.await() }
    }

    private suspend fun executeViaRoot(enabled: Boolean): TetheringHelper.Result =
        withContext(Dispatchers.IO) {
            try {
                val apkPath = appContext.packageCodePath
                val action = if (enabled) "on" else "off"
                val cmd = "CLASSPATH=\"$apkPath\" app_process /system/bin ${RootTetherMain::class.java.name} $action"
                val process = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()

                val finished = process.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@withContext TetheringHelper.Result(false, "Root 执行超时")
                }
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
                if (process.exitValue() == 0) {
                    TetheringHelper.Result(true)
                } else {
                    TetheringHelper.Result(false, output.ifBlank { "Root 执行失败 (exit=${process.exitValue()})" })
                }
            } catch (t: Throwable) {
                TetheringHelper.Result(false, "Root 执行异常: ${t.message}")
            }
        }

    suspend fun rejectIncomingCall(): TetheringHelper.Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val ended = runCatching {
                val tm = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                @Suppress("DEPRECATION")
                tm?.endCall() == true
            }.getOrDefault(false)
            if (ended) return@withContext TetheringHelper.Result(true)
        }

        if (isShizukuGranted()) {
            val svc = getOrBindShizukuService()
            if (svc != null && runCatching { svc.endCall() }.getOrDefault(false)) {
                return@withContext TetheringHelper.Result(true)
            }
        }

        if (isRootAvailable()) {
            val ok = runCatching {
                ProcessBuilder("su", "-c", "input keyevent 6").start().waitFor(3, TimeUnit.SECONDS)
            }.getOrDefault(false)
            if (ok) return@withContext TetheringHelper.Result(true)
        }

        TetheringHelper.Result(false, "缺少接听/挂断电话权限或 Shizuku/Root 服务")
    }

    suspend fun silenceIncomingCall(): TetheringHelper.Result = withContext(Dispatchers.IO) {
        if (isShizukuGranted()) {
            val svc = getOrBindShizukuService()
            if (svc != null && runCatching { svc.silenceRinger() }.getOrDefault(false)) {
                return@withContext TetheringHelper.Result(true)
            }
        }

        if (isRootAvailable()) {
            val ok = runCatching {
                ProcessBuilder("su", "-c", "input keyevent 25").start().waitFor(3, TimeUnit.SECONDS)
            }.getOrDefault(false)
            if (ok) return@withContext TetheringHelper.Result(true)
        }

        val muted = runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            am != null
        }.getOrDefault(false)

        if (muted) TetheringHelper.Result(true) else TetheringHelper.Result(false, "来电静音失败")
    }

    fun unbind() {
        runCatching {
            if (isShizukuAvailable()) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }
        }
        shizukuService = null
    }
}
