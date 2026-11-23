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
        _settings.Save();

        DialogResult = true;
        Close();
    }
}
