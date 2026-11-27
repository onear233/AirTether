using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using AirTether.Models;

namespace AirTether.Services;

/// <summary>
/// AirDrop-like bidirectional fast file transfer orchestrated via BLE handshake
/// and executed over local Hotspot / Wi-Fi LAN HTTP (no admin privileges required).
/// </summary>
public sealed class FileTransferService : IDisposable
{
    private readonly BleHotspotClient _client;
    private readonly AppSettings _settings;
    private readonly WifiAutoConnector _wifiConnector;
    private readonly IncomingCallNotifier _notifier;
    private readonly Func<string> _resolveTargetSsid;

    private TcpListener? _activeListener;
    private CancellationTokenSource? _serveCts;
    private readonly string _downloadDir;

    public event Action<string>? StatusChanged;

    public FileTransferService(
        BleHotspotClient client,
        AppSettings settings,
        WifiAutoConnector wifiConnector,
        IncomingCallNotifier notifier,
        Func<string> resolveTargetSsid)
    {
        _client = client;
        _settings = settings;
        _wifiConnector = wifiConnector;
        _notifier = notifier;
        _resolveTargetSsid = resolveTargetSsid;

        string userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        _downloadDir = Path.Combine(userProfile, "Downloads", "AirTether");
    }

    /// <summary>
    /// Direction A: Phone -> PC fast transfer triggered by BLE "xfer_ready" event.
    /// </summary>
    public void HandleIncomingTransferOffer(string ip, int port, string token, string fileName, long fileSize, bool needHotspot)
    {
        _ = Task.Run(async () =>
        {
            string safeName = SanitizeFileName(fileName);
            Directory.CreateDirectory(_downloadDir);
            string destPath = GetUniquePath(Path.Combine(_downloadDir, safeName));

            string sizeText = FormatFileSize(fileSize);
            StatusChanged?.Invoke($"正在接收手机文件: {safeName} ({sizeText})...");
            _notifier.ShowFileTransferToast("正在接收手机文件", $"{safeName} ({sizeText})");

            string url = $"http://{ip}:{port}/file?token={token}";
            bool wifiConnectTriggered = false;

            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };

            for (int attempt = 1; attempt <= 12; attempt++)
            {
                try
                {
                    using var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
                    if (resp.IsSuccessStatusCode)
                    {
                        await using var netStream = await resp.Content.ReadAsStreamAsync().ConfigureAwait(false);
                        await using var fileStream = File.Create(destPath);
                        await netStream.CopyToAsync(fileStream).ConfigureAwait(false);
                        await fileStream.FlushAsync().ConfigureAwait(false);

                        StatusChanged?.Invoke($"已接收文件: {safeName}");
                        _notifier.ShowFileTransferToast("文件接收完成", $"{safeName} 已保存到 Downloads\\AirTether", _downloadDir);
                        await _client.SendEventJsonAsync(JsonSerializer.Serialize(new { t = "xfer_done", ok = true })).ConfigureAwait(false);
                        return;
                    }
                }
                catch
                {
                    // If unreachable on first failure, auto-connect to phone's Wi-Fi hotspot
                    if (!wifiConnectTriggered && (needHotspot || _settings.AutoConnectHotspotWifi))
                    {
                        wifiConnectTriggered = true;
                        string ssid = _resolveTargetSsid();
                        if (!string.IsNullOrWhiteSpace(ssid))
                        {
                            StatusChanged?.Invoke($"正在通过热点 {ssid} 建立高速传输通道...");
                            _wifiConnector.TriggerConnect(ssid);
                        }
                    }
                }

                await Task.Delay(2000).ConfigureAwait(false);
            }

            StatusChanged?.Invoke($"接收文件失败: {safeName}（无法连接手机传输端口）");
            _notifier.ShowFileTransferToast("文件接收失败", $"无法连接到手机 ({ip}:{port})，请确认已连接同一网络或手机热点");
        });
    }

    /// <summary>
    /// Direction B: PC -> Phone fast transfer.
    /// Starts an ephemeral local HTTP server, ensures Hotspot/LAN connectivity, and sends BLE handshake.
    /// </summary>
    public async Task SendFileToPhoneAsync(string filePath)
    {
        if (!File.Exists(filePath) || _client.ConnectionState != BleConnectionState.Connected)
            return;

        StopServer();

        var fileInfo = new FileInfo(filePath);
        string fileName = fileInfo.Name;
        long fileSize = fileInfo.Length;
        string token = Guid.NewGuid().ToString("N")[..10];

        // If phone hotspot is off and PC has no Wi-Fi IP, or auto-connect is enabled and hotspot is off:
        // Prefer existing Wi-Fi LAN IP if available; otherwise auto-open hotspot & connect PC!
        string? localIp = FindPreferredLocalIp();
        if (string.IsNullOrWhiteSpace(localIp) && !_client.CurrentStatus.HotspotEnabled)
        {
            StatusChanged?.Invoke("无网快传：正在开启手机热点并连接...");
            await _client.SendCommandAsync(BleCommand.HotspotOn).ConfigureAwait(false);
            string ssid = _resolveTargetSsid();
            if (!string.IsNullOrWhiteSpace(ssid))
            {
                _wifiConnector.TriggerConnect(ssid);
            }

            for (int i = 0; i < 10; i++)
            {
                await Task.Delay(1500).ConfigureAwait(false);
                localIp = FindPreferredLocalIp();
                if (!string.IsNullOrWhiteSpace(localIp)) break;
            }
        }

        if (string.IsNullOrWhiteSpace(localIp))
        {
            StatusChanged?.Invoke("快传失败：未找到可用的局域网或热点 IP");
            _notifier.ShowFileTransferToast("快传启动失败", "电脑未连接到 Wi-Fi 或手机热点");
            return;
        }

        var listener = new TcpListener(IPAddress.Any, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;

        var cts = new CancellationTokenSource(TimeSpan.FromMinutes(2));
        _activeListener = listener;
        _serveCts = cts;

        _ = Task.Run(() => ServeFileLoopAsync(listener, filePath, fileSize, token, cts.Token), cts.Token);

        StatusChanged?.Invoke($"正在发送文件到手机: {fileName} ({FormatFileSize(fileSize)})...");
        string handshakeJson = JsonSerializer.Serialize(new
        {
            t = "xfer_pull_start",
            ip = localIp,
            port,
            token,
            name = fileName,
            size = fileSize
        });

        bool sent = await _client.SendEventJsonAsync(handshakeJson).ConfigureAwait(false);
        if (!sent)
        {
            StopServer();
            StatusChanged?.Invoke("发送快传指令失败");
        }
    }

    public void OnPhoneTransferAck(bool ok, string message)
    {
        StopServer();
        if (ok)
        {
            StatusChanged?.Invoke($"文件已发送到手机 ({message})");
            _notifier.ShowFileTransferToast("文件发送成功", $"手机已保存: {message}");
        }
        else
        {
            StatusChanged?.Invoke($"手机接收文件失败: {message}");
            _notifier.ShowFileTransferToast("文件发送失败", message);
        }
    }

    private async Task ServeFileLoopAsync(
        TcpListener listener,
        string filePath,
        long fileSize,
        string expectedToken,
        CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                TcpClient client;
                try
                {
                    client = await listener.AcceptTcpClientAsync(token).ConfigureAwait(false);
                }
                catch
                {
                    break;
                }

                using (client)
                await using (var stream = client.GetStream())
                {
                    client.ReceiveTimeout = 10000;
                    client.SendTimeout = 30000;

                    using var reader = new StreamReader(stream, Encoding.ASCII, leaveOpen: true);
                    string? requestLine = await reader.ReadLineAsync(token).ConfigureAwait(false);
                    if (string.IsNullOrWhiteSpace(requestLine)) continue;

                    while (true)
                    {
                        string? line = await reader.ReadLineAsync(token).ConfigureAwait(false);
                        if (string.IsNullOrEmpty(line)) break;
                    }

                    if (!requestLine.Contains($"token={expectedToken}", StringComparison.Ordinal))
                    {
                        byte[] forbidden = Encoding.ASCII.GetBytes("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                        await stream.WriteAsync(forbidden, token).ConfigureAwait(false);
                        continue;
                    }

                    string header =
                        "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        $"Content-Length: {fileSize}\r\n" +
                        "Connection: close\r\n\r\n";
                    byte[] headerBytes = Encoding.ASCII.GetBytes(header);
                    await stream.WriteAsync(headerBytes, token).ConfigureAwait(false);

                    await using var fs = File.OpenRead(filePath);
                    await fs.CopyToAsync(stream, 32 * 1024, token).ConfigureAwait(false);
                    await stream.FlushAsync(token).ConfigureAwait(false);
                }
            }
        }
        catch
        {
            // Cancelled or stopped
        }
        finally
        {
            try { listener.Stop(); } catch { }
        }
    }

    public void StopServer()
    {
        try { _serveCts?.Cancel(); } catch { }
        try { _serveCts?.Dispose(); } catch { }
        _serveCts = null;

        try { _activeListener?.Stop(); } catch { }
        _activeListener = null;
    }

    private static string? FindPreferredLocalIp()
    {
        var candidates = new List<(bool isWifi, string ip)>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback ||
                ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel)
                continue;

            bool isWifi = ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211;
            var ipProps = ni.GetIPProperties();
            foreach (var ua in ipProps.UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                string ip = ua.Address.ToString();
                if (ip.StartsWith("192.168.") || ip.StartsWith("10.") || ip.StartsWith("172."))
                {
                    candidates.Add((isWifi, ip));
                }
            }
        }
        if (candidates.Count == 0) return null;
        var wifi = candidates.FirstOrDefault(c => c.isWifi);
        return !string.IsNullOrEmpty(wifi.ip) ? wifi.ip : candidates[0].ip;
    }

    private static string SanitizeFileName(string name)
    {
        if (string.IsNullOrWhiteSpace(name)) return $"airtether_{DateTime.Now:yyyyMMdd_HHmmss}.bin";
        foreach (char c in Path.GetInvalidFileNameChars())
        {
            name = name.Replace(c, '_');
        }
        return name;
    }

    private static string GetUniquePath(string path)
    {
        if (!File.Exists(path)) return path;
        string dir = Path.GetDirectoryName(path) ?? "";
        string stem = Path.GetFileNameWithoutExtension(path);
        string ext = Path.GetExtension(path);
        for (int i = 1; i < 1000; i++)
        {
            string candidate = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(candidate)) return candidate;
        }
        return path;
    }

    private static string FormatFileSize(long bytes)
    {
        if (bytes < 0) return "未知大小";
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return $"{bytes / 1024.0:F1} KB";
        return $"{bytes / (1024.0 * 1024.0):F2} MB";
    }

    public void Dispose()
    {
        StopServer();
    }
}
