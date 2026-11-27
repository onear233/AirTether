using System.Runtime.InteropServices;
using AirTether.Models;
using Windows.Devices.Bluetooth.Advertisement;

namespace AirTether.Services;

/// <summary>
/// Monitors BLE signal strength (RSSI via passive watcher) and unexpected disconnect
/// to automatically lock Windows when the user walks away with their phone.
/// </summary>
public sealed class ProximityLockService : IDisposable
{
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool LockWorkStation();

    private readonly BleHotspotClient _client;
    private readonly AppSettings _settings;
    private readonly System.Threading.Timer _timer;

    private BluetoothLEAdvertisementWatcher? _passiveWatcher;
    private bool _armed;
    private DateTime? _outOfRangeSinceUtc;
    private short? _latestRssi;
    private DateTime _latestRssiUtc = DateTime.MinValue;
    private bool _disposed;

    public event Action<string>? StatusChanged;

    public ProximityLockService(BleHotspotClient client, AppSettings settings)
    {
        _client = client;
        _settings = settings;
        _client.StateChanged += OnClientStateChanged;
        _timer = new System.Threading.Timer(OnTick, null, 2000, 2000);
        UpdatePassiveWatcherState();
    }

    public void OnSettingsUpdated()
    {
        _outOfRangeSinceUtc = null;
        UpdatePassiveWatcherState();
    }

    private void OnClientStateChanged()
    {
        if (_client.ConnectionState == BleConnectionState.Connected)
        {
            _armed = true;
            _outOfRangeSinceUtc = null;
        }
        else if (_client.WasUserDisconnected)
        {
            _armed = false;
            _outOfRangeSinceUtc = null;
        }
        UpdatePassiveWatcherState();
    }

    private void UpdatePassiveWatcherState()
    {
        if (_disposed) return;

        bool shouldWatch = _settings.EnableProximityLock &&
            (_client.ConnectionState == BleConnectionState.Connected || _armed);

        if (shouldWatch && _passiveWatcher is null)
        {
            try
            {
                var w = new BluetoothLEAdvertisementWatcher
                {
                    ScanningMode = BluetoothLEScanningMode.Passive
                };
                w.Received += OnPassiveAdvertisement;
                w.Start();
                _passiveWatcher = w;
            }
            catch
            {
                // Non-fatal if adapter rejects concurrent passive watcher
            }
        }
        else if (!shouldWatch && _passiveWatcher is not null)
        {
            StopPassiveWatcher();
        }
    }

    private void StopPassiveWatcher()
    {
        var w = _passiveWatcher;
        _passiveWatcher = null;
        if (w is not null)
        {
            w.Received -= OnPassiveAdvertisement;
            try
            {
                w.Stop();
            }
            catch
            {
                // Ignore
            }
        }
    }

    private void OnPassiveAdvertisement(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
        bool matchesAddress = _client.ConnectedDeviceAddress.HasValue &&
            args.BluetoothAddress == _client.ConnectedDeviceAddress.Value;
        bool matchesLast = _settings.LastDeviceAddress.HasValue &&
            args.BluetoothAddress == _settings.LastDeviceAddress.Value;
        bool matchesName = !string.IsNullOrWhiteSpace(_client.ConnectedDeviceName) &&
            !string.IsNullOrWhiteSpace(args.Advertisement.LocalName) &&
            string.Equals(args.Advertisement.LocalName, _client.ConnectedDeviceName, StringComparison.OrdinalIgnoreCase);

        if (matchesAddress || matchesLast || matchesName)
        {
            _latestRssi = args.RawSignalStrengthInDBm;
            _latestRssiUtc = DateTime.UtcNow;
        }
    }

    private void OnTick(object? state)
    {
        if (_disposed || !_settings.EnableProximityLock || !_armed)
        {
            _outOfRangeSinceUtc = null;
            return;
        }

        if (_client.WasUserDisconnected)
        {
            _armed = false;
            _outOfRangeSinceUtc = null;
            return;
        }

        int threshold = Math.Clamp(_settings.ProximityRssiThreshold, -95, -55);
        int delaySec = Math.Clamp(_settings.ProximityLockDelaySeconds, 5, 60);

        bool awayByDisconnect = _client.ConnectionState == BleConnectionState.Disconnected;
        bool hasFreshRssi = _latestRssi.HasValue && (DateTime.UtcNow - _latestRssiUtc).TotalSeconds <= 8;
        bool awayByWeakRssi = hasFreshRssi && _latestRssi!.Value < threshold;

        if (awayByDisconnect || awayByWeakRssi)
        {
            _outOfRangeSinceUtc ??= DateTime.UtcNow;
            double elapsed = (DateTime.UtcNow - _outOfRangeSinceUtc.Value).TotalSeconds;
            if (elapsed >= delaySec)
            {
                _armed = false;
                _outOfRangeSinceUtc = null;
                StatusChanged?.Invoke("检测到手机已离开，已自动锁屏");
                LockWorkStation();
            }
        }
        else
        {
            _outOfRangeSinceUtc = null;
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _client.StateChanged -= OnClientStateChanged;
        _timer.Dispose();
        StopPassiveWatcher();
    }
}
