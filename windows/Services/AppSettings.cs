using System.IO;
using System.Text.Json;

namespace AirTether.Services;

public sealed class AppSettings
{
    private static readonly string SettingsDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "AirTether");

    private static readonly string SettingsPath = Path.Combine(SettingsDir, "settings.json");

    public ulong? LastDeviceAddress { get; set; }
    public string LastDeviceName { get; set; } = "";
    public bool AutoReconnect { get; set; } = true;
    public bool AutoConnectHotspotWifi { get; set; } = true;
    public string FallbackHotspotSsid { get; set; } = "";
    public string CustomNetworkText { get; set; } = "";

    // Feature #2: Proximity auto-lock
    public bool EnableProximityLock { get; set; } = false;
    public int ProximityRssiThreshold { get; set; } = -80;
    public int ProximityLockDelaySeconds { get; set; } = 12;

    // Feature #4: Clipboard / URL handoff
    public bool AutoOpenHandoffUrl { get; set; } = false;

    // Feature #5: Battery alerts & notification mirror
    public bool EnableBatteryAlerts { get; set; } = true;
    public int LowBatteryThreshold { get; set; } = 20;
    public bool EnableNotificationMirror { get; set; } = true;

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(SettingsPath))
            {
                var json = File.ReadAllText(SettingsPath);
                return JsonSerializer.Deserialize<AppSettings>(json) ?? new AppSettings();
            }
        }
        catch
        {
            // Ignore corrupted settings
        }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(SettingsDir);
            var json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(SettingsPath, json);
        }
        catch
        {
            // Ignore write errors
        }
    }
}
