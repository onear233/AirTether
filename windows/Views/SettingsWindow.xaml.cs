using System.Windows;
using AirTether.Services;

namespace AirTether.Views;

public partial class SettingsWindow : Window
{
    private readonly AppSettings _settings;

    public SettingsWindow(AppSettings settings)
    {
        InitializeComponent();
        _settings = settings;

        ChkAutoReconnect.IsChecked = _settings.AutoReconnect;
        ChkAutoConnectWifi.IsChecked = _settings.AutoConnectHotspotWifi;
        TbFallbackSsid.Text = _settings.FallbackHotspotSsid;

        ChkNotifMirror.IsChecked = _settings.EnableNotificationMirror;
        ChkAutoOpenUrl.IsChecked = _settings.AutoOpenHandoffUrl;
        ChkBatteryAlerts.IsChecked = _settings.EnableBatteryAlerts;
        TbLowBattery.Text = _settings.LowBatteryThreshold.ToString();

        ChkProximityLock.IsChecked = _settings.EnableProximityLock;
        TbRssiThreshold.Text = _settings.ProximityRssiThreshold.ToString();
        TbLockDelay.Text = _settings.ProximityLockDelaySeconds.ToString();
    }

    private void BtnCancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private void BtnSave_Click(object sender, RoutedEventArgs e)
    {
        _settings.AutoReconnect = ChkAutoReconnect.IsChecked == true;
        _settings.AutoConnectHotspotWifi = ChkAutoConnectWifi.IsChecked == true;
        _settings.FallbackHotspotSsid = TbFallbackSsid.Text.Trim();

        _settings.EnableNotificationMirror = ChkNotifMirror.IsChecked == true;
        _settings.AutoOpenHandoffUrl = ChkAutoOpenUrl.IsChecked == true;
        _settings.EnableBatteryAlerts = ChkBatteryAlerts.IsChecked == true;
        if (int.TryParse(TbLowBattery.Text.Trim(), out int lowBat))
        {
            _settings.LowBatteryThreshold = Math.Clamp(lowBat, 5, 50);
        }

        _settings.EnableProximityLock = ChkProximityLock.IsChecked == true;
        if (int.TryParse(TbRssiThreshold.Text.Trim(), out int rssi))
        {
            _settings.ProximityRssiThreshold = Math.Clamp(rssi, -95, -55);
        }
        if (int.TryParse(TbLockDelay.Text.Trim(), out int delay))
        {
            _settings.ProximityLockDelaySeconds = Math.Clamp(delay, 5, 60);
        }

        _settings.Save();

        DialogResult = true;
        Close();
    }
}
