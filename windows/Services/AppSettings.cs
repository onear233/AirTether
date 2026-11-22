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
    public string CustomNetworkText { get; set; } = "";

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
