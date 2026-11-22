package com.bthotspot.android.hotspot

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object TetheringHelper {
    private const val TETHERING_WIFI = 0

    data class Result(val success: Boolean, val error: String? = null)

    fun setHotspotEnabled(context: Context, enabled: Boolean): Result {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }

        val callerContext = resolveCallerContext(context)

        // Prefer TetheringManager on Android 11+ (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val tmResult = tryTetheringManager(callerContext, enabled)
            if (tmResult.success) return tmResult
            // Fall through to ConnectivityManager if TetheringManager failed due to missing API
            val cmResult = tryConnectivityManager(callerContext, enabled)
            return if (cmResult.success) cmResult else Result(false, "${tmResult.error}; ${cmResult.error}")
        }

        return tryConnectivityManager(callerContext, enabled)
    }

    private fun resolveCallerContext(context: Context): Context {
        val targetPkg = when (Process.myUid()) {
            Process.SHELL_UID -> "com.android.shell"
            0 -> "android"
            else -> return context
        }
        return object : ContextWrapper(context) {
            override fun getPackageName(): String = targetPkg
            override fun getOpPackageName(): String = targetPkg
            override fun getAttributionTag(): String? = null
        }
    }

    private fun overrideTetheringManagerContext(tetheringManager: Any, tmClass: Class<*>, callerContext: Context) {
        runCatching {
            val field = tmClass.getDeclaredField("mContext")
            field.isAccessible = true
            field.set(tetheringManager, callerContext)
        }
    }

    private fun tryTetheringManager(context: Context, enabled: Boolean): Result {
        return try {
            val tetheringManager = context.getSystemService("tethering")
                ?: return Result(false, "TetheringManager service unavailable")

            val tmClass = Class.forName("android.net.TetheringManager")
            overrideTetheringManagerContext(tetheringManager, tmClass, context)
            if (!enabled) {
                val stopMethod = tmClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                stopMethod.invoke(tetheringManager, TETHERING_WIFI)
                return Result(true)
            }

            val reqBuilderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = reqBuilderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            runCatching {
                reqBuilderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
                    .invoke(builder, false)
            }
            val request = reqBuilderClass.getMethod("build").invoke(builder)

            val reqClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val callbackInterface = Class.forName("android.net.TetheringManager\$StartTetheringCallback")

            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            val err = AtomicReference<String?>(null)

            val callbackProxy = Proxy.newProxyInstance(
                callbackInterface.classLoader,
                arrayOf(callbackInterface)
            ) { _, method, args ->
                when (method.name) {
                    "onTetheringStarted" -> {
                        ok.set(true)
                        latch.countDown()
                    }
                    "onTetheringFailed" -> {
                        val code = args?.firstOrNull()
                        ok.set(false)
                        err.set("TetheringManager.onTetheringFailed($code)")
                        latch.countDown()
                    }
                }
                null
            }

            val executor = Executor { runnable -> runnable.run() }
            val startMethod = tmClass.getMethod(
                "startTethering",
                reqClass,
                Executor::class.java,
                callbackInterface
            )
            startMethod.invoke(tetheringManager, request, executor, callbackProxy)

            val completed = latch.await(8, TimeUnit.SECONDS)
            if (!completed) {
                Result(false, "TetheringManager.startTethering timed out")
            } else if (ok.get()) {
                Result(true)
            } else {
                Result(false, err.get() ?: "TetheringManager failed")
            }
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Result(false, "TetheringManager error: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun tryConnectivityManager(context: Context, enabled: Boolean): Result {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Result(false, "ConnectivityManager unavailable")
            val cmClass = ConnectivityManager::class.java

            if (!enabled) {
                val stopMethod = cmClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                stopMethod.invoke(cm, TETHERING_WIFI)
                return Result(true)
            }

            val callbackClass = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
            val startMethod = cmClass.getMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                callbackClass,
                Handler::class.java
            )
            val handler = Handler(Looper.getMainLooper())
            startMethod.invoke(cm, TETHERING_WIFI, false, null, handler)
            Result(true)
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Result(false, "ConnectivityManager error: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }
}
