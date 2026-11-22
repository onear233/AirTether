package com.bthotspot.android.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.annotation.Keep

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
