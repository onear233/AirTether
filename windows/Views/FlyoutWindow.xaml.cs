using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using AirTether.Models;
using AirTether.Services;

namespace AirTether.Views;

public partial class FlyoutWindow : Window
{
    private readonly BleHotspotClient _client;
    private readonly AppSettings _settings;
    private readonly Func<Task> _toggleHotspotAsync;
    private readonly Func<Task> _sendClipboardAsync;
    private readonly Func<Task> _sendFileAsync;
    private readonly Action _openSettingsAction;
    private readonly Action _exitAction;

    public DateTime LastHiddenUtc { get; private set; } = DateTime.MinValue;

    public FlyoutWindow(
        BleHotspotClient client,
        AppSettings settings,
        Func<Task> toggleHotspotAsync,
        Func<Task> sendClipboardAsync,
        Func<Task> sendFileAsync,
        Action openSettingsAction,
        Action exitAction)
    {
        InitializeComponent();
        _client = client;
        _settings = settings;
        _toggleHotspotAsync = toggleHotspotAsync;
        _sendClipboardAsync = sendClipboardAsync;
        _sendFileAsync = sendFileAsync;
        _openSettingsAction = openSettingsAction;
        _exitAction = exitAction;

        Deactivated += (_, _) =>
        {
            LastHiddenUtc = DateTime.UtcNow;
            Hide();
        };

        SizeChanged += (_, _) =>
        {
            if (IsVisible)
            {
                PositionAboveTaskbar();
            }
        };

        _client.StateChanged += () => Dispatcher.BeginInvoke(RefreshUi);
        _client.DevicesUpdated += () => Dispatcher.BeginInvoke(RefreshDeviceList);

        RefreshUi();
        RefreshDeviceList();
    }

    public void SetWifiStatusMessage(string message)
    {
        Dispatcher.BeginInvoke(() =>
        {
            TbWifiStatus.Text = message;
            TbWifiStatus.ToolTip = string.IsNullOrWhiteSpace(message) ? null : message;
        });
    }

    public void ShowFlyout()
    {
        RefreshUi();
        RefreshDeviceList();
        if (!IsVisible)
        {
            Opacity = 0;
            Show();
            UpdateLayout();
            PositionAboveTaskbar();
            Opacity = 1;
        }
        else
        {
            PositionAboveTaskbar();
        }
        Activate();
    }

    public void PositionAboveTaskbar()
    {
        var workArea = SystemParameters.WorkArea;
        double w = ActualWidth > 0 ? ActualWidth : Width;
        double h = ActualHeight > 0 ? ActualHeight : 420;

        Left = workArea.Right - w - 10;
        Top = workArea.Bottom - h - 10;
    }

    public void RefreshUi()
    {
        var conn = _client.ConnectionState;
        var st = _client.CurrentStatus;
        bool connected = conn == BleConnectionState.Connected;

        // Signal bars
        int activeBars = connected ? Math.Clamp(st.SignalBars, 0, 4) : 0;
        var activeBrush = System.Windows.Media.Brushes.White;
        var dimBrush = new SolidColorBrush(System.Windows.Media.Color.FromArgb(68, 255, 255, 255));
        Border[] bars = [Bar1, Bar2, Bar3, Bar4];
        for (int i = 0; i < bars.Length; i++)
        {
            bars[i].Background = i < activeBars ? activeBrush : dimBrush;
        }

        TbNetTag.Text = connected ? st.MicroNetworkTag : "";

        // Header text
        TbDeviceName.Text = conn switch
        {
            BleConnectionState.Connected => string.IsNullOrWhiteSpace(_client.ConnectedDeviceName) ? "已连接手机" : _client.ConnectedDeviceName,
            BleConnectionState.Connecting => $"正在连接 {_client.ConnectedDeviceName}...",
            _ => "未连接手机"
        };

        TbSubStatus.Text = conn switch
        {
            BleConnectionState.Connected => $"网络: {st.NetworkLabel}  ·  信号: {st.SignalBars}/4",
            BleConnectionState.Connecting => "正在建立 BLE GATT 连接...",
            _ => _client.IsScanning ? "正在扫描附近的手机 BLE 服务..." : "点击“扫描设备”以连接手机"
        };

        if (connected && st.BatteryPercent >= 0)
        {
            TbBattery.Text = st.IsCharging ? $"充电中 {st.BatteryPercent}%" : $"电量 {st.BatteryPercent}%";
        }
        else
        {
            TbBattery.Text = "";
        }

        string displaySsid = !string.IsNullOrWhiteSpace(st.Ssid)
            ? st.Ssid
            : _settings.FallbackHotspotSsid;

        // Hotspot Tile & Quick Actions
        BtnHotspotTile.IsEnabled = connected;
        BtnSendClipboard.IsEnabled = connected;
        BtnSendFile.IsEnabled = connected;

        if (!connected)
        {
            BtnHotspotTile.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x2D, 0x2D, 0x2D));
            TbHotspotSubtitle.Text = "未连接手机";
            TbHotspotStatePill.Text = "不可用";
        }
        else if (st.IsTransitioning)
        {
            BtnHotspotTile.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0x5A, 0x9E));
            TbHotspotSubtitle.Text = "正在切换热点状态...";
            TbHotspotStatePill.Text = "切换中";
        }
        else if (st.HotspotEnabled)
        {
            BtnHotspotTile.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x00, 0x78, 0xD7));
            TbHotspotSubtitle.Text = string.IsNullOrWhiteSpace(displaySsid) ? "已开启" : $"已开启 · {displaySsid}";
            TbHotspotStatePill.Text = "开";
        }
        else
        {
            BtnHotspotTile.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x2D, 0x2D, 0x2D));
            TbHotspotSubtitle.Text = string.IsNullOrWhiteSpace(displaySsid) ? "已关闭 · 点击开启" : $"已关闭 · {displaySsid}";
            TbHotspotStatePill.Text = "关";
        }

        // Scan / Disconnect buttons
        BtnScan.Content = _client.IsScanning ? "停止扫描" : "扫描设备";
        BtnDisconnect.Visibility = conn != BleConnectionState.Disconnected ? Visibility.Visible : Visibility.Collapsed;

        // Footer note
        if (!string.IsNullOrWhiteSpace(_client.LastError))
        {
            TbFooterNote.Text = _client.LastError;
            TbFooterNote.Visibility = Visibility.Visible;
        }
        else
        {
            TbFooterNote.Visibility = Visibility.Collapsed;
        }
    }

    public void RefreshDeviceList()
    {
        var devices = _client.GetTargetDevices();
        LvDevices.ItemsSource = devices;
        TbEmptyDevices.Visibility = devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void BtnHotspotTile_Click(object sender, RoutedEventArgs e)
    {
        if (_client.ConnectionState != BleConnectionState.Connected) return;
        BtnHotspotTile.IsEnabled = false;
        await _toggleHotspotAsync();
        BtnHotspotTile.IsEnabled = _client.ConnectionState == BleConnectionState.Connected;
    }

    private async void BtnSendClipboard_Click(object sender, RoutedEventArgs e)
    {
        if (_client.ConnectionState != BleConnectionState.Connected) return;
        await _sendClipboardAsync();
    }

    private async void BtnSendFile_Click(object sender, RoutedEventArgs e)
    {
        if (_client.ConnectionState != BleConnectionState.Connected) return;
        Hide();
        await _sendFileAsync();
    }

    private void BtnScan_Click(object sender, RoutedEventArgs e)
    {
        if (_client.IsScanning)
        {
            _client.StopScan();
        }
        else
        {
            _client.StartScan();
        }
    }

    private async void BtnDisconnect_Click(object sender, RoutedEventArgs e)
    {
        await _client.DisconnectAsync();
    }

    private async void BtnConnectItem_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.Button { Tag: DiscoveredBleDevice dev })
        {
            await _client.ConnectAsync(dev.Address, dev.DisplayTitle);
        }
    }

    private async void LvDevices_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (LvDevices.SelectedItem is DiscoveredBleDevice dev)
        {
            await _client.ConnectAsync(dev.Address, dev.DisplayTitle);
        }
    }

    private void BtnSettings_Click(object sender, RoutedEventArgs e)
    {
        _openSettingsAction();
    }

    private void BtnExit_Click(object sender, RoutedEventArgs e)
    {
        _exitAction();
    }
}
