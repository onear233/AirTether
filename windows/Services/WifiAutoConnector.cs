using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;
using Windows.Devices.Radios;
using Windows.Devices.WiFi;

namespace AirTether.Services;

public sealed class WifiAutoConnector : IDisposable
{
    private CancellationTokenSource? _cts;

    public event Action<string>? StatusChanged;

    public void CancelPending()
    {
        try
        {
            _cts?.Cancel();
            _cts?.Dispose();
        }
        catch
        {
            // Ignore
        }
        _cts = null;
    }

    public void TriggerConnect(string targetSsid)
    {
        string ssid = targetSsid.Trim();
        if (string.IsNullOrWhiteSpace(ssid))
        {
            StatusChanged?.Invoke("未获取到热点 SSID，可在手机端或电脑端设置中填写");
            return;
        }

        CancelPending();
        var cts = new CancellationTokenSource();
        _cts = cts;
        var token = cts.Token;

        _ = Task.Run(() => ConnectLoopAsync(ssid, token), token);
    }

    private async Task ConnectLoopAsync(string ssid, CancellationToken token)
    {
        try
        {
            await EnsureWifiRadioEnabledAsync(token).ConfigureAwait(false);

            if (IsCurrentlyConnectedToSsid(ssid))
            {
                StatusChanged?.Invoke($"电脑已连接热点 Wi-Fi: {ssid}");
                return;
            }

            StatusChanged?.Invoke($"正在等待并自动连接热点 Wi-Fi: {ssid}...");

            IReadOnlyList<WiFiAdapter>? adapters = await TryGetWifiAdaptersAsync().ConfigureAwait(false);
            string? profileName = FindMatchingWlanProfileName(ssid) ?? ssid;

            for (int attempt = 1; attempt <= 8 && !token.IsCancellationRequested; attempt++)
            {
                if (IsCurrentlyConnectedToSsid(ssid))
                {
                    StatusChanged?.Invoke($"已自动连接热点 Wi-Fi: {ssid}");
                    return;
                }

                if (adapters is null || adapters.Count == 0)
                {
                    adapters = await TryGetWifiAdaptersAsync().ConfigureAwait(false);
                }

                // 1. Trigger hardware Wi-Fi scan via WinRT WiFiAdapter if available
                if (adapters is { Count: > 0 })
                {
                    foreach (var adapter in adapters)
                    {
                        if (token.IsCancellationRequested) return;
                        try
                        {
                            await adapter.ScanAsync();
                            var match = adapter.NetworkReport.AvailableNetworks
                                .FirstOrDefault(n => string.Equals(n.Ssid, ssid, StringComparison.OrdinalIgnoreCase));
                            if (match is not null)
                            {
                                _ = adapter.ConnectAsync(match, WiFiReconnectionKind.Automatic);
                            }
                        }
                        catch
                        {
                            // Ignore scan errors during adapter busy state
                        }
                    }
                }

                // 2. Also invoke netsh wlan connect using saved profile
                RunNetsh($"wlan connect name=\"{profileName}\" ssid=\"{ssid}\"");

                await Task.Delay(2200, token).ConfigureAwait(false);

                if (IsCurrentlyConnectedToSsid(ssid))
                {
                    StatusChanged?.Invoke($"已自动连接热点 Wi-Fi: {ssid}");
                    return;
                }
            }

            if (!token.IsCancellationRequested)
            {
                StatusChanged?.Invoke($"自动连接 Wi-Fi ({ssid}) 超时，请确认电脑已保存过该热点密码");
            }
        }
        catch (OperationCanceledException)
        {
            // Cancelled
        }
        catch (Exception ex)
        {
            StatusChanged?.Invoke($"自动连接 Wi-Fi 异常: {ex.Message}");
        }
    }

    private async Task EnsureWifiRadioEnabledAsync(CancellationToken token)
    {
        try
        {
            var access = await Radio.RequestAccessAsync();
            if (access != RadioAccessStatus.Allowed)
                return;

            var radios = await Radio.GetRadiosAsync();
            var wifiRadios = radios.Where(r => r.Kind == RadioKind.WiFi).ToList();
            if (wifiRadios.Count == 0)
                return;

            bool anyOn = wifiRadios.Any(r => r.State == RadioState.On);
            if (anyOn)
                return;

            StatusChanged?.Invoke("检测到电脑 Wi-Fi 未开启，正在开启 Wi-Fi...");
            bool turnedOnAny = false;
            foreach (var radio in wifiRadios)
            {
                if (token.IsCancellationRequested) return;
                if (radio.State == RadioState.Off)
                {
                    var result = await radio.SetStateAsync(RadioState.On);
                    if (result == RadioAccessStatus.Allowed)
                    {
                        turnedOnAny = true;
                    }
                }
            }

            if (turnedOnAny)
            {
                // Wait briefly for WLAN adapter and radio to finish initializing
                for (int i = 0; i < 10 && !token.IsCancellationRequested; i++)
                {
                    await Task.Delay(300, token).ConfigureAwait(false);
                    var refreshed = await Radio.GetRadiosAsync();
                    if (refreshed.Any(r => r.Kind == RadioKind.WiFi && r.State == RadioState.On))
                    {
                        await Task.Delay(1200, token).ConfigureAwait(false);
                        break;
                    }
                }
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            // Ignore radio control errors and continue attempting WLAN connection
        }
    }

    private static async Task<IReadOnlyList<WiFiAdapter>?> TryGetWifiAdaptersAsync()
    {
        try
        {
            var access = await WiFiAdapter.RequestAccessAsync();
            if (access == WiFiAccessStatus.Allowed)
            {
                return await WiFiAdapter.FindAllAdaptersAsync();
            }
        }
        catch
        {
            // Ignore if WinRT WiFi access is restricted; fallback to netsh
        }
        return null;
    }

    private static bool IsCurrentlyConnectedToSsid(string targetSsid)
    {
        string output = RunNetsh("wlan show interfaces");
        if (string.IsNullOrWhiteSpace(output)) return false;

        foreach (var rawLine in output.Split('\n'))
        {
            string line = rawLine.Trim();
            // Match "SSID : <name>" but not "BSSID : <mac>"
            if (line.StartsWith("SSID", StringComparison.OrdinalIgnoreCase) &&
                !line.StartsWith("BSSID", StringComparison.OrdinalIgnoreCase))
            {
                int colon = line.IndexOf(':');
                if (colon >= 0)
                {
                    string current = line[(colon + 1)..].Trim();
                    if (string.Equals(current, targetSsid, StringComparison.OrdinalIgnoreCase))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static string? FindMatchingWlanProfileName(string targetSsid)
    {
        string output = RunNetsh("wlan show profiles");
        if (string.IsNullOrWhiteSpace(output)) return null;

        var matches = Regex.Matches(output, @":\s*(.+)$", RegexOptions.Multiline);
        foreach (Match m in matches)
        {
            string name = m.Groups[1].Value.Trim();
            if (string.Equals(name, targetSsid, StringComparison.OrdinalIgnoreCase))
            {
                return name;
            }
        }
        return null;
    }

    private static string RunNetsh(string arguments)
    {
        try
        {
            var psi = new ProcessStartInfo("netsh", arguments)
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8
            };
            using var proc = Process.Start(psi);
            if (proc is null) return "";
            string output = proc.StandardOutput.ReadToEnd();
            proc.WaitForExit(3000);
            return output;
        }
        catch
        {
            return "";
        }
    }

    public void Dispose()
    {
        CancelPending();
    }
}
