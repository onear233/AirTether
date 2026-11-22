package com.bthotspot.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        updatePermissionStatus()
        localMonitor?.refreshSnapshot()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        ServiceStateHolder.addLog(if (granted) "Shizuku 权限已授予" else "Shizuku 权限被拒绝")
        updatePrivilegeStatus()
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        updatePrivilegeStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hotspotController = HotspotController(this)
        localMonitor = PhoneStateMonitor(this, hotspotController).also { it.start() }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)

        setupUi()
        observeState()
        updatePermissionStatus()
        checkPrivileges()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        localMonitor?.stop()
        hotspotController.unbind()
    }

    private fun setupUi() {
        when (hotspotController.mode) {
            HotspotMode.AUTO -> binding.rbModeAuto.isChecked = true
            HotspotMode.ROOT -> binding.rbModeRoot.isChecked = true
            HotspotMode.SHIZUKU -> binding.rbModeShizuku.isChecked = true
        }

        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbModeRoot -> HotspotMode.ROOT
                R.id.rbModeShizuku -> HotspotMode.SHIZUKU
                else -> HotspotMode.AUTO
            }
            hotspotController.mode = mode
            ServiceStateHolder.addLog("控制模式切换为: ${mode.displayName}")
        }

        binding.btnRequestPerms.setOnClickListener {
            requestRuntimePermissions()
        }

        binding.btnRequestShizuku.setOnClickListener {
            if (!hotspotController.isShizukuAvailable()) {
                Toast.makeText(this, "Shizuku 服务未运行，请先启动 Shizuku", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (hotspotController.isShizukuGranted()) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            } else {
                Shizuku.requestPermission(100)
            }
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
                    Toast.makeText(this, "请先授予所需权限", Toast.LENGTH_SHORT).show()
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
    }

    private fun testHotspot(enable: Boolean) {
        val label = if (enable) "开启" else "关闭"
        ServiceStateHolder.addLog("本地测试${label}热点 (${hotspotController.mode.displayName})...")
        lifecycleScope.launch {
            val result = hotspotController.setHotspotEnabled(enable)
            if (result.success) {
                ServiceStateHolder.addLog("本地${label}热点成功")
                Toast.makeText(this@MainActivity, "热点${label}成功", Toast.LENGTH_SHORT).show()
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
                binding.tvBleState.text = when {
                    !running -> "BLE 服务: 未运行"
                    count > 0 -> "BLE 服务: 运行中 · 已连接 $count 台电脑"
                    adv -> "BLE 服务: 广播中 · 等待电脑连接"
                    else -> "BLE 服务: 运行中 (未广播)"
                }
            }
        }

        lifecycleScope.launch {
            val monitorFlow = localMonitor?.status ?: ServiceStateHolder.status
            combine(ServiceStateHolder.isRunning, ServiceStateHolder.status, monitorFlow) { running, svcSt, locSt ->
                if (running) svcSt else locSt
            }.collect { st ->
                val hsText = when {
                    st.isTransitioning -> "切换中..."
                    st.hotspotEnabled -> "已开启"
                    else -> "已关闭"
                }
                val ssidPart = if (st.ssid.isNotBlank()) " (${st.ssid})" else ""
                binding.tvHotspotState.text = "便携式热点: $hsText$ssidPart"
                val batText = if (st.batteryPercent >= 0) "${st.batteryPercent}%" else "--%"
                binding.tvNetworkState.text =
                    "移动网络: ${st.networkType.label} | 信号: ${st.signalBars}/4 | 电量: $batText"
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
            Toast.makeText(this, "所需运行时权限已全部授予", Toast.LENGTH_SHORT).show()
            updatePermissionStatus()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun updatePermissionStatus() {
        val missing = missingPermissions()
        binding.tvPermStatus.text = if (missing.isEmpty()) {
            "运行时权限: 已全部授予"
        } else {
            "运行时权限: 缺少 ${missing.size} 项 (${missing.joinToString { it.substringAfterLast('.') }})"
        }
    }

    private fun checkPrivileges() {
        updatePrivilegeStatus()
        lifecycleScope.launch {
            rootAvailable = hotspotController.isRootAvailable()
            updatePrivilegeStatus()
        }
    }

    private fun updatePrivilegeStatus() {
        val rootText = when (rootAvailable) {
            null -> "检测中"
            true -> "可用"
            false -> "不可用"
        }
        val shizukuText = when {
            !hotspotController.isShizukuAvailable() -> "未运行"
            hotspotController.isShizukuGranted() -> "已授权"
            else -> "运行中 (未授权)"
        }
        binding.tvPrivilegeStatus.text = "Root: $rootText | Shizuku: $shizukuText"
    }
}
