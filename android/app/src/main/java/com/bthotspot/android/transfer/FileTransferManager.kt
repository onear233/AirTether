package com.bthotspot.android.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.bthotspot.android.ble.ServiceStateHolder
import com.bthotspot.android.hotspot.HotspotController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.util.UUID

class FileTransferManager(
    private val context: Context,
    private val hotspotController: HotspotController
) {
    private val appContext = context.applicationContext

    @Volatile
    private var activeServer: ServerSocket? = null

    @Volatile
    private var autoEnabledHotspotForTransfer = false

    data class OutgoingTransfer(
        val ip: String,
        val port: Int,
        val token: String,
        val fileName: String,
        val fileSize: Long,
        val needHotspot: Boolean
    ) {
        fun toJson(): String = JSONObject().apply {
            put("t", "xfer_ready")
            put("ip", ip)
            put("port", port)
            put("token", token)
            put("name", fileName)
            put("size", fileSize)
            put("needHotspot", needHotspot)
        }.toString()
    }

    /**
     * Prepares a local HTTP server to serve [uri] to Windows PC.
     * Automatically turns on Wi-Fi Hotspot if not on a local network or if hotspot is preferred.
     */
    suspend fun startOutgoingFile(uri: Uri): OutgoingTransfer? = withContext(Dispatchers.IO) {
        stopServer()

        val (fileName, fileSize) = queryFileMeta(uri)
        val token = UUID.randomUUID().toString().replace("-", "").take(10)

        var (hsOn, _) = hotspotController.getHotspotState()
        var needHotspot = false
        var localIp = findPreferredLocalIp(preferHotspot = hsOn)

        if (!hsOn && localIp == null) {
            ServiceStateHolder.addLog("无网快传：自动开启手机热点...")
            val res = hotspotController.setHotspotEnabled(true)
            if (res.success) {
                autoEnabledHotspotForTransfer = true
                needHotspot = true
                hsOn = true
                hotspotController.refreshAutoSsid()
                repeat(8) {
                    delay(500)
                    localIp = findPreferredLocalIp(preferHotspot = true)
                    if (localIp != null) return@repeat
                }
            }
        } else if (!hsOn) {
            // Even if on Wi-Fi LAN, try turning on hotspot if user wants direct link? No, LAN is faster if same subnet;
            // Windows will try LAN IP first and fall back to Hotspot SSID if unreachable.
            needHotspot = false
        } else {
            needHotspot = true
        }

        val ip = localIp ?: "192.168.43.1"

        val server = runCatching { ServerSocket(0) }.getOrNull() ?: return@withContext null
        server.soTimeout = 120_000
        activeServer = server
        val port = server.localPort

        ServiceStateHolder.addLog("快传服务已就绪: http://$ip:$port ($fileName)")

        // Serve file in background thread (allows retries from PC within 2 minutes)
        Thread {
            serveLoop(server, uri, token, fileName, fileSize)
        }.apply {
            isDaemon = true
            start()
        }

        OutgoingTransfer(ip, port, token, fileName, fileSize, needHotspot)
    }

    private fun serveLoop(
        server: ServerSocket,
        uri: Uri,
        expectedToken: String,
        fileName: String,
        fileSize: Long
    ) {
        try {
            val deadline = System.currentTimeMillis() + 120_000L
            while (!server.isClosed && System.currentTimeMillis() < deadline) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                socket.use { s ->
                    s.soTimeout = 30_000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val requestLine = reader.readLine() ?: return@use
                    // Drain headers
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }

                    val out = s.getOutputStream()
                    if (!requestLine.contains("token=$expectedToken")) {
                        val resp = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(resp.toByteArray())
                        out.flush()
                        return@use
                    }

                    val input = appContext.contentResolver.openInputStream(uri)
                    if (input == null) {
                        val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(resp.toByteArray())
                        out.flush()
                        return@use
                    }

                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/octet-stream\r\n")
                        if (fileSize >= 0) {
                            append("Content-Length: $fileSize\r\n")
                        }
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray(Charsets.UTF_8))

                    input.use { stream ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val read = stream.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                    }
                    out.flush()
                    ServiceStateHolder.addLog("文件已发送到电脑: $fileName")
                }
            }
        } catch (_: Throwable) {
            // Closed or timed out
        } finally {
            runCatching { server.close() }
        }
    }

    /**
     * Downloads a file hosted by Windows PC into Download/AirTether/<fileName>.
     */
    suspend fun downloadIncomingFile(
        ip: String,
        port: Int,
        token: String,
        fileName: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val safeName = fileName.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "airtether_file.bin" }
        val urlStr = "http://$ip:$port/file?token=$token"
        ServiceStateHolder.addLog("开始接收电脑文件: $safeName ($ip:$port)")

        var lastErr = "无法连接到电脑"
        for (attempt in 1..6) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 30000
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val savedPath = saveStreamToDownloads(safeName, BufferedInputStream(conn.inputStream))
                    conn.disconnect()
                    ServiceStateHolder.addLog("已保存电脑文件: $savedPath")
                    return@withContext true to savedPath
                } else {
                    lastErr = "HTTP ${conn.responseCode}"
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                lastErr = t.message ?: t.javaClass.simpleName
            }
            delay(1500)
        }
        ServiceStateHolder.addLog("接收电脑文件失败: $lastErr")
        false to lastErr
    }

    private fun saveStreamToDownloads(fileName: String, input: BufferedInputStream): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AirTether")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    copyStream(input, out)
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "Download/AirTether/$fileName"
            }
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AirTether"
        )
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, fileName)
        FileOutputStream(dest).use { out ->
            copyStream(input, out)
        }
        return dest.absolutePath
    }

    private fun copyStream(input: BufferedInputStream, output: OutputStream) {
        input.use { src ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = src.read(buf)
                if (read <= 0) break
                output.write(buf, 0, read)
            }
            output.flush()
        }
    }

    suspend fun onTransferCompletedByPc() {
        stopServer()
        if (autoEnabledHotspotForTransfer) {
            autoEnabledHotspotForTransfer = false
            ServiceStateHolder.addLog("快传完成，自动关闭临时热点")
            hotspotController.setHotspotEnabled(false)
        }
    }

    fun stopServer() {
        runCatching { activeServer?.close() }
        activeServer = null
    }

    private fun queryFileMeta(uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = -1L
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun findPreferredLocalIp(preferHotspot: Boolean): String? {
        val candidates = mutableListOf<Pair<String, String>>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val ifaceName = iface.name.lowercase()
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            candidates.add(ifaceName to ip)
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null
        if (preferHotspot) {
            candidates.firstOrNull { (name, ip) ->
                name.contains("ap") || name.contains("softap") || name.contains("wlan2") || ip.startsWith("192.168.43.")
            }?.let { return it.second }
        }
        candidates.firstOrNull { (name, _) -> name.startsWith("wlan") }?.let { return it.second }
        return candidates.first().second
    }
}
