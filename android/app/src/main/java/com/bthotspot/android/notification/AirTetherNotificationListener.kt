package com.bthotspot.android.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bthotspot.android.ble.ServiceStateHolder
import org.json.JSONObject

object NotificationMirrorPrefs {
    private const val PREFS_NAME = "bt_hotspot_prefs"
    private const val KEY_ENABLED = "notif_mirror_enabled"
    private const val KEY_WHITELIST = "notif_mirror_whitelist"

    private val DEFAULT_WHITELIST = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        "com.eg.android.AlipayGphone"
    )

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getWhitelist(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_WHITELIST, null)?.toSet() ?: DEFAULT_WHITELIST
    }

    fun setWhitelist(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_WHITELIST, packages.toSet()).apply()
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        val cn = ComponentName(context, AirTetherNotificationListener::class.java)
        return flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString())
    }
}

class AirTetherNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: AirTetherNotificationListener? = null

        @Volatile
        var onMirrorJsonReady: ((String) -> Unit)? = null

        fun cancelNotificationByKey(key: String): Boolean {
            val svc = instance ?: return false
            return runCatching {
                svc.cancelNotification(key)
                true
            }.getOrDefault(false)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        ServiceStateHolder.addLog("通知镜像监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!NotificationMirrorPrefs.isEnabled(this)) return

        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val notification = sbn.notification ?: return
        // Skip ongoing notifications and group summaries
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val whitelist = NotificationMirrorPrefs.getWhitelist(this)
        if (!whitelist.contains(pkg)) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val appName = runCatching {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(pkg)

        val json = JSONObject().apply {
            put("t", "notif")
            put("key", sbn.key ?: "")
            put("pkg", pkg)
            put("app", appName.take(32))
            put("title", title.take(80))
            put("text", text.take(240))
        }.toString()

        onMirrorJsonReady?.invoke(json)
    }
}
