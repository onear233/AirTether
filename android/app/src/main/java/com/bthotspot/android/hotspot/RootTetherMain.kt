package com.bthotspot.android.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Invoked via `su -c "CLASSPATH=<apk> app_process /system/bin com.bthotspot.android.hotspot.RootTetherMain <on|off>"`.
 */
@Keep
object RootTetherMain {
    @SuppressLint("PrivateApi")
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: RootTetherMain <on|off>")
            exitProcess(2)
        }
        val enable = when (args[0].lowercase()) {
            "on", "1", "true" -> true
            "off", "0", "false" -> false
            else -> {
                System.err.println("Invalid argument: ${args[0]}")
                exitProcess(2)
            }
        }

        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper()
            }
            val atClass = Class.forName("android.app.ActivityThread")
            val systemMain = atClass.getMethod("systemMain").invoke(null)
            val context = atClass.getMethod("getSystemContext").invoke(systemMain) as Context
            val result = TetheringHelper.setHotspotEnabled(context, enable)
            if (result.success) {
                println("OK")
                exitProcess(0)
            } else {
                System.err.println(result.error ?: "Unknown error")
                exitProcess(1)
            }
        } catch (t: Throwable) {
            System.err.println("RootTetherMain exception: ${t.javaClass.simpleName}: ${t.message}")
            exitProcess(1)
        }
    }
}
