using System.Diagnostics;
using System.Drawing;
using System.Windows;
using System.Windows.Forms;
using AirTether.Models;
using AirTether.Services;
using AirTether.Tray;
using AirTether.Views;

namespace AirTether;

public partial class App : System.Windows.Application
{
    private const string MutexName = "Global\\AirTetherTrayAppMutex";
    private const string ActivateEventName = "Global\\AirTetherShowFlyoutEvent";

    private Mutex? _singleInstanceMutex;
    private EventWaitHandle? _activateEvent;
    private AppSettings? _settings;
    private BleHotspotClient? _client;
    private IncomingCallNotifier? _callNotifier;
    private WifiAutoConnector? _wifiConnector;
    private FlyoutWindow? _flyout;

    private NotifyIcon? _signalNotifyIcon;
    private NotifyIcon? _networkNotifyIcon;
    private Icon? _currentSignalIcon;
    private Icon? _currentNetworkIcon;

    private bool _lastKnownHotspotEnabled;
    private bool _pendingAutoConnect;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _singleInstanceMutex = new Mutex(true, MutexName, out bool createdNew);
        if (!createdNew)
        {
            try
            {
                using var evt = EventWaitHandle.OpenExisting(ActivateEventName);
                evt.Set();
            }
            catch
            {
                // Ignore if event is unavailable
            }
            Shutdown();
            return;
        }

        _activateEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ActivateEventName);
        ThreadPool.RegisterWaitForSingleObject(_activateEvent, (_, _) =>
        {
            Dispatcher.BeginInvoke(() => _flyout?.ShowFlyout());
        }, null, -1, false);

        _settings = AppSettings.Load();
        _client = new BleHotspotClient(_settings);
        _callNotifier = new IncomingCallNotifier(_client);
        _wifiConnector = new WifiAutoConnector();
        _flyout = new FlyoutWindow(_client, _settings, ToggleHotspotFromUiAsync, ExitApplication);

        _wifiConnector.StatusChanged += msg => _flyout.SetWifiStatusMessage(msg);

        SetupTrayIcons();

        _client.StateChanged += () => Dispatcher.BeginInvoke(OnClientStateChanged);
        _client.CallStateChanged += call => Dispatcher.BeginInvoke(() =>
            _callNotifier?.HandleCallState(call, _client?.ConnectedDeviceName ?? "手机"));
        UpdateTrayIcons();

        if (_settings.AutoReconnect && _settings.LastDeviceAddress.HasValue)
        {
            _client.StartScan();
        }

        if (Debugger.IsAttached)
        {
            _flyout.ShowFlyout();
        }
    }

    private async Task ToggleHotspotFromUiAsync()
    {
        if (_client is null || _settings is null || _client.ConnectionState != BleConnectionState.Connected)
            return;

        bool turningOn = !_client.CurrentStatus.HotspotEnabled;
        if (turningOn && _settings.AutoConnectHotspotWifi)
        {
            _pendingAutoConnect = true;
            string ssid = ResolveTargetSsid();
            if (!string.IsNullOrWhiteSpace(ssid))
            {
                _wifiConnector?.TriggerConnect(ssid);
            }
        }
        else if (!turningOn)
        {
            _pendingAutoConnect = false;
            _wifiConnector?.CancelPending();
            _flyout?.SetWifiStatusMessage("");
        }

        await _client.SendCommandAsync(turningOn ? BleCommand.HotspotOn : BleCommand.HotspotOff);
    }

    private void OnClientStateChanged()
    {
        UpdateTrayIcons();

        if (_client is null || _settings is null) return;

        if (_client.ConnectionState != BleConnectionState.Connected)
        {
            _lastKnownHotspotEnabled = false;
            _pendingAutoConnect = false;
            return;
        }

        var st = _client.CurrentStatus;
        bool becameEnabled = st.HotspotEnabled && !st.IsTransitioning && !_lastKnownHotspotEnabled;
        if ((becameEnabled || (_pendingAutoConnect && st.HotspotEnabled && !st.IsTransitioning)) &&
            _settings.AutoConnectHotspotWifi)
        {
            _pendingAutoConnect = false;
            string ssid = ResolveTargetSsid();
            _wifiConnector?.TriggerConnect(ssid);
        }
        else if (!st.HotspotEnabled && !st.IsTransitioning && !_pendingAutoConnect)
        {
            _wifiConnector?.CancelPending();
        }

        if (!st.IsTransitioning)
        {
            _lastKnownHotspotEnabled = st.HotspotEnabled;
        }
    }

    private string ResolveTargetSsid()
    {
        if (_settings is not null && !string.IsNullOrWhiteSpace(_settings.FallbackHotspotSsid))
        {
            return _settings.FallbackHotspotSsid.Trim();
        }
        return _client?.CurrentStatus.Ssid.Trim() ?? "";
    }

    private void SetupTrayIcons()
    {
        var signalMenu = new ContextMenuStrip();
        signalMenu.Items.Add("打开控制面板", null, (_, _) => _flyout?.ShowFlyout());
        signalMenu.Items.Add("开关手机热点", null, async (_, _) =>
        {
            if (_client?.ConnectionState == BleConnectionState.Connected)
            {
                await ToggleHotspotFromUiAsync();
            }
            else
            {
                _flyout?.ShowFlyout();
            }
        });
        signalMenu.Items.Add("设置...", null, (_, _) => OpenSettingsDialog());
        signalMenu.Items.Add(new ToolStripSeparator());
        signalMenu.Items.Add("退出", null, (_, _) => ExitApplication());

        _signalNotifyIcon = new NotifyIcon
        {
            Visible = true,
            ContextMenuStrip = signalMenu,
            Text = "AirTether - 信号"
        };
        _signalNotifyIcon.MouseClick += OnTrayIconMouseClick;

        var networkMenu = new ContextMenuStrip();
        networkMenu.Items.Add("自定义显示文本...", null, (_, _) => OpenCustomNetworkTextDialog());
        networkMenu.Items.Add("恢复自动显示 (跟随手机)", null, (_, _) =>
        {
            if (_settings is null) return;
            _settings.CustomNetworkText = "";
            _settings.Save();
            UpdateTrayIcons();
        });
        networkMenu.Items.Add(new ToolStripSeparator());
        networkMenu.Items.Add("打开控制面板", null, (_, _) => _flyout?.ShowFlyout());
        networkMenu.Items.Add("设置...", null, (_, _) => OpenSettingsDialog());
        networkMenu.Items.Add("退出", null, (_, _) => ExitApplication());

        _networkNotifyIcon = new NotifyIcon
        {
            Visible = true,
            ContextMenuStrip = networkMenu,
            Text = "AirTether - 网络制式"
        };
        _networkNotifyIcon.MouseClick += OnTrayIconMouseClick;
    }

    private void OpenSettingsDialog()
    {
        if (_settings is null) return;
        _flyout?.Hide();
        var dlg = new SettingsWindow(_settings);
        dlg.ShowDialog();
        _flyout?.RefreshUi();
    }

    private void OnTrayIconMouseClick(object? sender, MouseEventArgs args)
    {
        if (args.Button != MouseButtons.Left || _flyout is null) return;

        if (_flyout.IsVisible)
        {
            _flyout.Hide();
        }
        else if ((DateTime.UtcNow - _flyout.LastHiddenUtc).TotalMilliseconds > 220)
        {
            _flyout.ShowFlyout();
        }
    }

    private void OpenCustomNetworkTextDialog()
    {
        if (_settings is null) return;
        var dlg = new CustomTextWindow(_settings.CustomNetworkText);
        if (dlg.ShowDialog() == true)
        {
            _settings.CustomNetworkText = dlg.ResultText;
            _settings.Save();
            UpdateTrayIcons();
        }
    }

    private void UpdateTrayIcons()
    {
        if (_client is null || _settings is null) return;

        if (_signalNotifyIcon is not null)
        {
            var newSig = TrayIconRenderer.RenderSignalIcon(_client.ConnectionState, _client.CurrentStatus);
            var oldSig = _currentSignalIcon;
            _currentSignalIcon = newSig;
            _signalNotifyIcon.Icon = newSig;
            oldSig?.Dispose();
        }

        if (_networkNotifyIcon is not null)
        {
            var newNet = TrayIconRenderer.RenderNetworkTextIcon(
                _client.ConnectionState,
                _client.CurrentStatus,
                _settings.CustomNetworkText);
            var oldNet = _currentNetworkIcon;
            _currentNetworkIcon = newNet;
            _networkNotifyIcon.Icon = newNet;
            oldNet?.Dispose();
        }

        string tooltip;
        if (_client.ConnectionState == BleConnectionState.Connected)
        {
            var st = _client.CurrentStatus;
            string hs = st.HotspotEnabled ? "热点已开" : "热点已关";
            string bat = st.BatteryPercent >= 0 ? $" · 电量{st.BatteryPercent}%" : "";
            tooltip = $"{_client.ConnectedDeviceName}\n{st.NetworkLabel} ({st.SignalBars}/4) · {hs}{bat}";
        }
        else if (_client.ConnectionState == BleConnectionState.Connecting)
        {
            tooltip = $"正在连接 {_client.ConnectedDeviceName}...";
        }
        else
        {
            tooltip = "AirTether - 未连接手机";
        }

        string clamped = tooltip.Length > 63 ? tooltip[..63] : tooltip;
        if (_signalNotifyIcon is not null) _signalNotifyIcon.Text = clamped;
        if (_networkNotifyIcon is not null)
        {
            string netTip = string.IsNullOrWhiteSpace(_settings.CustomNetworkText)
                ? clamped
                : $"自定义: {_settings.CustomNetworkText}\n{clamped}";
            _networkNotifyIcon.Text = netTip.Length > 63 ? netTip[..63] : netTip;
        }
    }

    private void ExitApplication()
    {
        DisposeNotifyIcon(ref _signalNotifyIcon, ref _currentSignalIcon);
        DisposeNotifyIcon(ref _networkNotifyIcon, ref _currentNetworkIcon);

        _wifiConnector?.Dispose();
        _flyout?.Close();
        _client?.Dispose();
        _activateEvent?.Dispose();
        _singleInstanceMutex?.ReleaseMutex();
        _singleInstanceMutex?.Dispose();
        Shutdown();
    }

    private static void DisposeNotifyIcon(ref NotifyIcon? notifyIcon, ref Icon? currentIcon)
    {
        if (notifyIcon is not null)
        {
            notifyIcon.Visible = false;
            notifyIcon.Dispose();
            notifyIcon = null;
        }
        currentIcon?.Dispose();
        currentIcon = null;
    }

    protected override void OnExit(ExitEventArgs e)
    {
        DisposeNotifyIcon(ref _signalNotifyIcon, ref _currentSignalIcon);
        DisposeNotifyIcon(ref _networkNotifyIcon, ref _currentNetworkIcon);
        _wifiConnector?.Dispose();
        _client?.Dispose();
        base.OnExit(e);
    }
}
