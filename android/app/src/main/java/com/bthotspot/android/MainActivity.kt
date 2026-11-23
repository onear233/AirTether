package com.bthotspot.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bthotspot.android.ble.BleHotspotService
import com.bthotspot.android.ble.ServiceStateHolder
import com.bthotspot.android.databinding.ActivityMainBinding
import com.bthotspot.android.hotspot.HotspotController
import com.bthotspot.android.hotspot.HotspotMode
import com.bthotspot.android.monitor.PhoneStateMonitor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hotspotController: HotspotController
    private var localMonitor: PhoneStateMonitor? = null
    private var rootAvailable: Boolean? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateAuthAndModeUi()
        localMonitor?.refreshSnapshot()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        ServiceStateHolder.addLog(if (granted) "Shizuku 权限已授予" else "Shizuku 权限被拒绝")
        updateAuthAndModeUi()
        if (granted) {
            triggerAutoSsidRefresh(silent = true)
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        updateAuthAndModeUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupImmersiveStatusBar()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        hotspotController = HotspotController(this)
        localMonitor = PhoneStateMonitor(this, hotspotController).also { it.start() }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)

        setupUi()
        observeState()
        updateAuthAndModeUi()
        checkPrivilegesAndSsid()
    }

    @Suppress("DEPRECATION")
    private fun setupImmersiveStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    private fun applySystemBarInsets() {
        val density = resources.displayMetrics.density
        val pad18 = (18 * density).toInt()
        val pad12 = (12 * density).toInt()

        // Disable BottomNavigationView's default internal inset padding so we control it cleanly
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, navInsets.bottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, 0, bars.right, 0)
            binding.topHeaderBar.setPadding(pad18, bars.top + pad12, pad18, pad12)
            windowInsets
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        localMonitor?.stop()
        hotspotController.unbind()
    }

    private fun setupUi() {
        // Bottom Navigation Bar switching (3 Tabs: 控制台 / 运行日志 / 设置)
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.tabDashboard.visibility = View.VISIBLE
                    binding.tabLogs.visibility = View.GONE
                    binding.tabSettings.visibility = View.GONE
                    true
                }
                R.id.nav_logs -> {
                    binding.tabDashboard.visibility = View.GONE
                    binding.tabLogs.visibility = View.VISIBLE
                    binding.tabSettings.visibility = View.GONE
                    true
                }
                R.id.nav_settings -> {
                    binding.tabDashboard.visibility = View.GONE
                    binding.tabLogs.visibility = View.GONE
                    binding.tabSettings.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }

        // Mode toggle group (inside Settings tab)
        val initialBtnId = when (hotspotController.mode) {
            HotspotMode.AUTO -> R.id.btnModeAuto
            HotspotMode.SHIZUKU -> R.id.btnModeShizuku
            HotspotMode.ROOT -> R.id.btnModeRoot
        }
        binding.toggleModeGroup.check(initialBtnId)

        binding.toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnModeRoot -> HotspotMode.ROOT
                R.id.btnModeShizuku -> HotspotMode.SHIZUKU
                else -> HotspotMode.AUTO
            }
            if (hotspotController.mode != mode) {
                hotspotController.mode = mode
                ServiceStateHolder.addLog("控制模式切换为: ${mode.displayName}")
                updateAuthAndModeUi()
            }
        }

        binding.btnRequestPerms.setOnClickListener {
            requestRuntimePermissions()
        }

        binding.btnRequestShizuku.setOnClickListener {
            requestOrCheckShizuku()
        }

        binding.btnSettingsCheckPerms.setOnClickListener {
            val missing = missingPermissions()
            if (missing.isEmpty()) {
                Toast.makeText(this, "所有运行时权限均已授予", Toast.LENGTH_SHORT).show()
            } else {
                requestRuntimePermissions()
            }
        }

        binding.btnSettingsCheckShizuku.setOnClickListener {
            checkPrivilegesAndSsid()
            requestOrCheckShizuku()
        }

        binding.btnToggleService.setOnClickListener {
            if (ServiceStateHolder.isRunning.value) {
                val intent = Intent(this, BleHotspotService::class.java).apply {
                    action = BleHotspotService.ACTION_STOP
                }
                startService(intent)
            } else {
                if (missingPermissions().isNotEmpty()) {
                    requestRuntimePermissions()
                    Toast.makeText(this, "请先授予所需运行时权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val intent = Intent(this, BleHotspotService::class.java).apply {
                    action = BleHotspotService.ACTION_START
                }
                ContextCompat.startForegroundService(this, intent)
            }
        }

        binding.btnTestHotspotOn.setOnClickListener { testHotspot(true) }
        binding.btnTestHotspotOff.setOnClickListener { testHotspot(false) }

        // Hotspot SSID configuration (inside Settings tab)
        binding.etManualSsid.setText(hotspotController.manualSsid)
        updateSsidInfoText()

        binding.btnRefreshSsid.setOnClickListener {
            triggerAutoSsidRefresh(silent = false)
        }

        binding.btnSaveManualSsid.setOnClickListener {
            val input = binding.etManualSsid.text?.toString()?.trim() ?: ""
            hotspotController.manualSsid = input
            localMonitor?.refreshSnapshot()
            updateSsidInfoText()
            val msg = if (input.isBlank()) "已清除手动 SSID，将使用自动读取值" else "已保存热点 SSID: $input"
            ServiceStateHolder.addLog(msg)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            ServiceStateHolder.clearLogs()
        }
    }

    private fun requestOrCheckShizuku() {
        if (!hotspotController.isShizukuAvailable()) {
            Toast.makeText(this, "Shizuku 服务未运行，请先启动 Shizuku", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hotspotController.isShizukuGranted()) {
            Shizuku.requestPermission(100)
        } else {
            Toast.makeText(this, "Shizuku 已授权就绪", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerAutoSsidRefresh(silent: Boolean) {
        lifecycleScope.launch {
            val ssid = hotspotController.refreshAutoSsid()
            localMonitor?.refreshSnapshot()
            updateSsidInfoText()
            if (!silent) {
                if (ssid.isNotBlank()) {
                    ServiceStateHolder.addLog("自动读取到热点 SSID: $ssid")
                    Toast.makeText(this@MainActivity, "读取成功: $ssid", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "未能从系统读取 SSID，可在下方手动填写兜底", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateSsidInfoText() {
        val auto = hotspotController.cachedAutoSsid
        val manual = hotspotController.manualSsid
        binding.tvAutoSsidInfo.text = when {
            manual.isNotBlank() && auto.isNotBlank() ->
                "当前生效: $manual (手动指定) · 系统读取: $auto"
            manual.isNotBlank() ->
                "当前生效: $manual (手动兜底) · 系统读取: 未读取到"
            auto.isNotBlank() ->
                "当前生效: $auto (系统自动读取)"
            else ->
                "系统读取: 未读取到（可在下方手动填写热点名称供电脑端自动连接）"
        }
    }

    private fun testHotspot(enable: Boolean) {
        val label = if (enable) "开启" else "关闭"
        ServiceStateHolder.addLog("本地测试${label}热点 (${hotspotController.mode.displayName})...")
        lifecycleScope.launch {
            val result = hotspotController.setHotspotEnabled(enable)
            if (result.success) {
                ServiceStateHolder.addLog("本地${label}热点成功")
                Toast.makeText(this@MainActivity, "热点${label}成功", Toast.LENGTH_SHORT).show()
                if (enable) {
                    hotspotController.refreshAutoSsid()
                    updateSsidInfoText()
                }
            } else {
                ServiceStateHolder.addLog("本地${label}热点失败: ${result.error}")
                Toast.makeText(this@MainActivity, "失败: ${result.error}", Toast.LENGTH_LONG).show()
            }
            localMonitor?.refreshSnapshot()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            combine(
                ServiceStateHolder.isRunning,
                ServiceStateHolder.isAdvertising,
                ServiceStateHolder.connectedCount
            ) { running, adv, count ->
                Triple(running, adv, count)
            }.collect { (running, adv, count) ->
                binding.btnToggleService.text = if (running) "停止 BLE 后台服务" else "启动 BLE 后台服务"
                when {
                    !running -> {
                        binding.tvBleState.text = "服务未启动 · 点击下方按钮开启广播"
                        binding.tvBleBadge.text = "已停止"
                        binding.tvBleBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
                    }
                    count > 0 -> {
                        binding.tvBleState.text = "正在协同 · 已连接 $count 台电脑"
                        binding.tvBleBadge.text = "已连接 ($count)"
                        binding.tvBleBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                    }
                    adv -> {
                        binding.tvBleState.text = "BLE 广播中 · 等待电脑端连接"
                        binding.tvBleBadge.text = "广播中"
                        binding.tvBleBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue))
                    }
                    else -> {
                        binding.tvBleState.text = "服务运行中 (广播未就绪)"
                        binding.tvBleBadge.text = "运行中"
                        binding.tvBleBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_amber))
                    }
                }
            }
        }

        lifecycleScope.launch {
            val monitorFlow = localMonitor?.status ?: ServiceStateHolder.status
            combine(ServiceStateHolder.isRunning, ServiceStateHolder.status, monitorFlow) { running, svcSt, locSt ->
                if (running) svcSt else locSt
            }.collect { st ->
                binding.tvHotspotState.text = when {
                    st.isTransitioning -> "切换中..."
                    st.hotspotEnabled -> "已开启"
                    else -> "已关闭"
                }
                binding.tvHotspotSsidSub.text =
                    if (st.ssid.isNotBlank()) "SSID: ${st.ssid}" else "SSID: 未设置（可在设置页填写）"
                binding.tvNetTypeValue.text = st.networkType.label
                binding.tvSignalValue.text = "${st.signalBars} / 4 格"
                binding.tvBatteryValue.text =
                    if (st.batteryPercent >= 0) "${st.batteryPercent}%" else "--%"
            }
        }

        lifecycleScope.launch {
            ServiceStateHolder.logs.collect { lines ->
                binding.tvLogs.text = if (lines.isEmpty()) "暂无日志" else lines.joinToString("\n")
            }
        }
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    private fun missingPermissions(): List<String> {
        return requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRuntimePermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            updateAuthAndModeUi()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkPrivilegesAndSsid() {
        updateAuthAndModeUi()
        lifecycleScope.launch {
            rootAvailable = hotspotController.isRootAvailable()
            updateAuthAndModeUi()
            triggerAutoSsidRefresh(silent = true)
        }
    }

    private fun updateAuthAndModeUi() {
        val missingPerms = missingPermissions()
        val hasMissingPerms = missingPerms.isNotEmpty()
        binding.layoutPermWarning.visibility = if (hasMissingPerms) View.VISIBLE else View.GONE
        if (hasMissingPerms) {
            binding.tvPermWarning.text = "缺少 ${missingPerms.size} 项运行时权限（蓝牙 / 电话状态）"
        }

        val shizukuRunning = hotspotController.isShizukuAvailable()
        val shizukuGranted = hotspotController.isShizukuGranted()
        val isRootOk = rootAvailable == true
        val mode = hotspotController.mode

        val (statusText, statusColorRes, privWarnText, showShizukuBtn) = when (mode) {
            HotspotMode.SHIZUKU -> when {
                shizukuGranted -> Quad("当前运行：Shizuku 已就绪", R.color.status_green, null, false)
                shizukuRunning -> Quad("未就绪：Shizuku 待授权", R.color.status_amber, "当前为 Shizuku 模式，请点击右侧按钮完成授权", true)
                else -> Quad("未就绪：Shizuku 未启动", R.color.status_red, "当前为 Shizuku 模式，请先启动 Shizuku 服务", false)
            }
            HotspotMode.ROOT -> when (rootAvailable) {
                null -> Quad("正在检测 Root...", R.color.status_amber, null, false)
                true -> Quad("当前运行：Root 已就绪", R.color.status_green, null, false)
                false -> Quad("未就绪：无 Root 权限", R.color.status_red, "当前为 Root 模式，但未检测到 Root 权限", false)
            }
            HotspotMode.AUTO -> when {
                shizukuGranted -> Quad("当前运行：Shizuku 已就绪", R.color.status_green, null, false)
                isRootOk -> Quad("当前运行：Root 已就绪", R.color.status_green, null, false)
                rootAvailable == null -> Quad("正在检测运行环境...", R.color.status_amber, null, false)
                shizukuRunning -> Quad("未就绪：待授权 Shizuku", R.color.status_amber, "检测到 Shizuku 正在运行，请授权以启用热点控制", true)
                else -> Quad("未就绪：缺少 Shizuku 或 Root", R.color.status_red, "请启动并授权 Shizuku（或授予 Root 权限）以控制热点", false)
            }
        }

        val color = ContextCompat.getColor(this, statusColorRes)
        binding.tvActiveModeStatus.text = statusText
        binding.tvActiveModeStatus.setTextColor(color)
        binding.tvSettingsModeStatus.text = statusText
        binding.tvSettingsModeStatus.setTextColor(color)

        val hasPrivWarning = privWarnText != null
        binding.layoutPrivWarning.visibility = if (hasPrivWarning) View.VISIBLE else View.GONE
        if (hasPrivWarning) {
            binding.tvPrivWarning.text = privWarnText
            binding.btnRequestShizuku.visibility = if (showShizukuBtn) View.VISIBLE else View.GONE
        }

        binding.cardAuthWarning.visibility =
            if (hasMissingPerms || hasPrivWarning) View.VISIBLE else View.GONE
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
