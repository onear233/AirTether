package com.bthotspot.android.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass

@Keep
class ShizukuHotspotUserService() : IHotspotUserService.Stub() {

    private var serviceContext: Context? = null
    @Volatile
    private var lastError: String = ""

    @Keep
    constructor(context: Context) : this() {
        this.serviceContext = context
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun setHotspotEnabled(enabled: Boolean): Boolean {
        val ctx = resolveContext()
        if (ctx == null) {
            lastError = "Unable to obtain Context inside Shizuku UserService"
            return false
        }
        val result = TetheringHelper.setHotspotEnabled(ctx, enabled)
        lastError = result.error ?: ""
        return result.success
    }

    override fun getLastError(): String = lastError

    override fun endCall(): Boolean {
        return try {
            val process = ProcessBuilder("input", "keyevent", "6").start()
            process.waitFor() == 0
        } catch (t: Throwable) {
            lastError = "endCall failed: ${t.message}"
            false
        }
    }

    override fun silenceRinger(): Boolean {
        return try {
            val process = ProcessBuilder("input", "keyevent", "25").start()
            process.waitFor() == 0
        } catch (t: Throwable) {
            lastError = "silenceRinger failed: ${t.message}"
            false
        }
    }

    override fun getHotspotSsid(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        val ctx = resolveContext()
        if (ctx != null) {
            runCatching {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val cfg = wm.javaClass.getMethod("getSoftApConfiguration").invoke(wm)
                        if (cfg != null) {
                            val ssid = cfg.javaClass.getMethod("getSsid").invoke(cfg) as? String
                            if (!ssid.isNullOrBlank()) return ssid.trim('"')
                        }
                    }
                    val legacyCfg = wm.javaClass.getMethod("getWifiApConfiguration").invoke(wm)
                    if (legacyCfg != null) {
                        val ssid = legacyCfg.javaClass.getField("SSID").get(legacyCfg) as? String
                        if (!ssid.isNullOrBlank()) return ssid.trim('"')
                    }
                }
            }
        }

        // Fallback: parse dumpsys wifi for SoftApConfiguration SSID
        runCatching {
            val proc = ProcessBuilder("dumpsys", "wifi").redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            val regexes = listOf(
                Regex("""SoftApConfiguration[^\n]*?[sS]sid\s*[=:]\s*"?([^",\n\r\}]+)"?"""),
                Regex("""mWifiApConfig[^\n]*?SSID\s*[=:]\s*"?([^",\n\r\}]+)"?""")
            )
            for (rx in regexes) {
                val m = rx.find(text)
                val candidate = m?.groupValues?.getOrNull(1)?.trim()?.trim('"') ?: ""
                if (candidate.isNotBlank() && !candidate.equals("null", true) && !candidate.contains("unknown", true)) {
                    return candidate
                }
            }
        }
        return ""
    }

    @SuppressLint("PrivateApi")
    private fun resolveContext(): Context? {
        serviceContext?.let { return it }
        return try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper()
            }
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getMethod("currentApplication").invoke(null) as? Context
            if (currentApp != null) {
                serviceContext = currentApp
                return currentApp
            }
            val systemMain = atClass.getMethod("systemMain").invoke(null)
            val sysCtx = atClass.getMethod("getSystemContext").invoke(systemMain) as? Context
            serviceContext = sysCtx
            sysCtx
        } catch (t: Throwable) {
            lastError = "Context resolution failed: ${t.message}"
            null
        }
    }
}
