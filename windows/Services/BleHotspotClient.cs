using System.Collections.Concurrent;
using System.Runtime.InteropServices.WindowsRuntime;
using AirTether.Models;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace AirTether.Services;

public sealed class BleHotspotClient : IDisposable
{
    private readonly AppSettings _settings;
    private readonly ConcurrentDictionary<ulong, DiscoveredBleDevice> _seenDevices = new();
    private readonly SemaphoreSlim _connectLock = new(1, 1);

    private BluetoothLEAdvertisementWatcher? _watcher;
    private BluetoothLEDevice? _device;
    private GattSession? _gattSession;
    private GattDeviceService? _service;
    private GattCharacteristic? _statusChar;
    private GattCharacteristic? _commandChar;
    private GattCharacteristic? _callChar;
    private GattCharacteristic? _eventUpChar;
    private GattCharacteristic? _eventDownChar;
    private readonly BleFrameCodec _frameCodec = new();
    private readonly SemaphoreSlim _eventWriteLock = new(1, 1);
    private CancellationTokenSource? _pollCts;

    private int _autoConnectInFlight;
    private int _disconnectHandling;
    private DateTime _lastAutoConnectAttemptUtc = DateTime.MinValue;
    private DateTime _lastStatusActivityUtc = DateTime.MinValue;
    private bool _userRequestedDisconnect;
    private bool _disposed;

    public event Action? StateChanged;
    public event Action? DevicesUpdated;
    public event Action<CallInfo>? CallStateChanged;
    public event Action<string>? EventJsonReceived;
    public event Action<string>? MessageLogged;

    public BleConnectionState ConnectionState { get; private set; } = BleConnectionState.Disconnected;
    public HotspotStatus CurrentStatus { get; private set; } = new();
    public CallInfo CurrentCall { get; private set; } = new();
    public string ConnectedDeviceName { get; private set; } = "";
    public ulong? ConnectedDeviceAddress { get; private set; }
    public bool WasUserDisconnected => _userRequestedDisconnect;
    public bool IsScanning { get; private set; }
    public string LastError { get; private set; } = "";

    public BleHotspotClient(AppSettings settings)
    {
        _settings = settings;
    }

    public IReadOnlyList<DiscoveredBleDevice> GetTargetDevices()
    {
        var cutoff = DateTime.UtcNow.AddSeconds(-45);
        return _seenDevices.Values
            .Where(d => d.HasTargetService && d.LastSeen >= cutoff)
            .OrderByDescending(d => d.Address == _settings.LastDeviceAddress)
            .ThenByDescending(d => d.Rssi)
            .ToList();
    }

    public void StartScan()
    {
        if (_disposed || IsScanning) return;

        try
        {
            var watcher = new BluetoothLEAdvertisementWatcher
            {
                ScanningMode = BluetoothLEScanningMode.Active
            };
            watcher.Received += OnAdvertisementReceived;
            watcher.Stopped += OnWatcherStopped;
            watcher.Start();
            _watcher = watcher;
            IsScanning = true;
            LastError = "";
            Log("开始扫描 BLE 热点设备...");
            StateChanged?.Invoke();
        }
        catch (Exception ex)
        {
            LastError = $"无法启动蓝牙扫描: {ex.Message}";
            Log(LastError);
            StateChanged?.Invoke();
        }
    }

    public void StopScan()
    {
        var watcher = _watcher;
        _watcher = null;
        if (watcher is not null)
        {
            watcher.Received -= OnAdvertisementReceived;
            watcher.Stopped -= OnWatcherStopped;
            try
            {
                watcher.Stop();
            }
            catch
            {
                // Ignore
            }
        }
        if (IsScanning)
        {
            IsScanning = false;
            StateChanged?.Invoke();
        }
    }

    private void OnWatcherStopped(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementWatcherStoppedEventArgs args)
    {
        if (ReferenceEquals(_watcher, sender))
        {
            IsScanning = false;
            if (args.Error != BluetoothError.Success)
            {
                LastError = $"蓝牙扫描停止: {args.Error}";
            }
            StateChanged?.Invoke();
        }
    }

    private void OnAdvertisementReceived(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
        ulong addr = args.BluetoothAddress;
        var entry = _seenDevices.GetOrAdd(addr, _ => new DiscoveredBleDevice
        {
            Address = addr,
            FormattedAddress = FormatMacAddress(addr)
        });

        entry.Rssi = args.RawSignalStrengthInDBm;
        entry.LastSeen = DateTime.UtcNow;

        if (!string.IsNullOrWhiteSpace(args.Advertisement.LocalName))
        {
            entry.Name = args.Advertisement.LocalName;
        }

        if (args.Advertisement.ServiceUuids.Contains(BleProtocol.ServiceUuid))
        {
            entry.HasTargetService = true;
        }

        if (!entry.HasTargetService) return;

        DevicesUpdated?.Invoke();

        if (!_settings.AutoReconnect ||
            _userRequestedDisconnect ||
            ConnectionState != BleConnectionState.Disconnected)
        {
            return;
        }

        bool matchesLastMac = _settings.LastDeviceAddress.HasValue && _settings.LastDeviceAddress.Value == addr;
        bool matchesLastName = !string.IsNullOrWhiteSpace(_settings.LastDeviceName) &&
            !string.IsNullOrWhiteSpace(entry.Name) &&
            string.Equals(entry.Name, _settings.LastDeviceName, StringComparison.OrdinalIgnoreCase);

        if (!matchesLastMac && !matchesLastName) return;

        // Enforce cooldown and prevent concurrent reconnect storms from rapid advertisements
        if ((DateTime.UtcNow - _lastAutoConnectAttemptUtc).TotalSeconds < 3.0) return;
        if (Interlocked.CompareExchange(ref _autoConnectInFlight, 1, 0) != 0) return;

        _lastAutoConnectAttemptUtc = DateTime.UtcNow;
        string targetName = entry.DisplayTitle;
        _ = Task.Run(async () =>
        {
            try
            {
                await ConnectAsync(addr, targetName).ConfigureAwait(false);
            }
            finally
            {
                Interlocked.Exchange(ref _autoConnectInFlight, 0);
            }
        });
    }

    public async Task ConnectAsync(ulong address, string? displayName = null)
    {
        if (_disposed) return;
        _userRequestedDisconnect = false;

        await _connectLock.WaitAsync().ConfigureAwait(false);
        try
        {
            if (ConnectionState == BleConnectionState.Connected && ConnectedDeviceAddress == address)
                return;

            await DisconnectInternalAsync().ConfigureAwait(false);

            ConnectionState = BleConnectionState.Connecting;
            ConnectedDeviceAddress = address;
            ConnectedDeviceName = !string.IsNullOrWhiteSpace(displayName)
                ? displayName
                : (_seenDevices.TryGetValue(address, out var seen) ? seen.DisplayTitle : FormatMacAddress(address));
            LastError = "";
            StateChanged?.Invoke();
            Log($"正在连接 {ConnectedDeviceName}...");

            // Stop active scanning during connection handshake to avoid 2.4GHz radio contention
            StopScan();

            var dev = await BluetoothLEDevice.FromBluetoothAddressAsync(address);
            if (dev is null)
            {
                throw new InvalidOperationException("无法创建 BluetoothLEDevice 实例");
            }
            _device = dev;
            _device.ConnectionStatusChanged += OnDeviceConnectionStatusChanged;

            try
            {
                _gattSession = await GattSession.FromDeviceIdAsync(dev.BluetoothDeviceId);
                if (_gattSession is not null)
                {
                    _gattSession.MaintainConnection = true;
                    _gattSession.SessionStatusChanged += OnGattSessionStatusChanged;
                }
            }
            catch
            {
                // Non-fatal if GattSession fails
            }

            if (!string.IsNullOrWhiteSpace(dev.Name))
            {
                ConnectedDeviceName = dev.Name;
            }

            var servicesResult = await dev.GetGattServicesForUuidAsync(
                BleProtocol.ServiceUuid,
                BluetoothCacheMode.Uncached);

            if (servicesResult.Status != GattCommunicationStatus.Success || servicesResult.Services.Count == 0)
            {
                throw new InvalidOperationException($"未找到热点 GATT 服务 ({servicesResult.Status})");
            }

            _service = servicesResult.Services[0];

            var statusChars = await _service.GetCharacteristicsForUuidAsync(
                BleProtocol.StatusCharUuid,
                BluetoothCacheMode.Uncached);
            var cmdChars = await _service.GetCharacteristicsForUuidAsync(
                BleProtocol.CommandCharUuid,
                BluetoothCacheMode.Uncached);

            if (statusChars.Status != GattCommunicationStatus.Success || statusChars.Characteristics.Count == 0 ||
                cmdChars.Status != GattCommunicationStatus.Success || cmdChars.Characteristics.Count == 0)
            {
                throw new InvalidOperationException("GATT 特征值不完整");
            }

            _statusChar = statusChars.Characteristics[0];
            _commandChar = cmdChars.Characteristics[0];

            _statusChar.ValueChanged += OnStatusCharacteristicValueChanged;
            var cccdStatus = await _statusChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.Notify);
            if (cccdStatus != GattCommunicationStatus.Success)
            {
                Log($"订阅通知警告: {cccdStatus}");
            }

            var readResult = await _statusChar.ReadValueAsync(BluetoothCacheMode.Uncached);
            if (readResult.Status == GattCommunicationStatus.Success)
            {
                _lastStatusActivityUtc = DateTime.UtcNow;
                ApplyStatusBuffer(readResult.Value);
            }

            var callChars = await _service.GetCharacteristicsForUuidAsync(
                BleProtocol.CallCharUuid,
                BluetoothCacheMode.Uncached);
            if (callChars.Status == GattCommunicationStatus.Success && callChars.Characteristics.Count > 0)
            {
                _callChar = callChars.Characteristics[0];
                _callChar.ValueChanged += OnCallCharacteristicValueChanged;
                await _callChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.Notify);
            }

            var eventUpChars = await _service.GetCharacteristicsForUuidAsync(
                BleProtocol.EventUpCharUuid,
                BluetoothCacheMode.Uncached);
            if (eventUpChars.Status == GattCommunicationStatus.Success && eventUpChars.Characteristics.Count > 0)
            {
                _eventUpChar = eventUpChars.Characteristics[0];
                _eventUpChar.ValueChanged += OnEventUpCharacteristicValueChanged;
                await _eventUpChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.Notify);
            }

            var eventDownChars = await _service.GetCharacteristicsForUuidAsync(
                BleProtocol.EventDownCharUuid,
                BluetoothCacheMode.Uncached);
            if (eventDownChars.Status == GattCommunicationStatus.Success && eventDownChars.Characteristics.Count > 0)
            {
                _eventDownChar = eventDownChars.Characteristics[0];
            }

            ConnectionState = BleConnectionState.Connected;
            _lastStatusActivityUtc = DateTime.UtcNow;
            _settings.LastDeviceAddress = address;
            _settings.LastDeviceName = ConnectedDeviceName;
            _settings.Save();

            StartStatusKeepAlive();
            Log($"已连接 {ConnectedDeviceName}");
            StateChanged?.Invoke();
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            Log($"连接失败: {ex.Message}");
            await DisconnectInternalAsync().ConfigureAwait(false);
            if (_settings.AutoReconnect && !_userRequestedDisconnect && !_disposed)
            {
                ScheduleReconnectScan(2000);
            }
        }
        finally
        {
            _connectLock.Release();
        }
    }

    public async Task DisconnectAsync()
    {
        _userRequestedDisconnect = true;
        StopScan();
        await _connectLock.WaitAsync().ConfigureAwait(false);
        try
        {
            await DisconnectInternalAsync().ConfigureAwait(false);
            Log("已断开蓝牙连接");
        }
        finally
        {
            _connectLock.Release();
        }
    }

    private async Task DisconnectInternalAsync()
    {
        StopStatusKeepAlive();

        if (_statusChar is not null)
        {
            _statusChar.ValueChanged -= OnStatusCharacteristicValueChanged;
            try
            {
                await _statusChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.None);
            }
            catch
            {
                // Ignore cleanup errors
            }
            _statusChar = null;
        }

        if (_callChar is not null)
        {
            _callChar.ValueChanged -= OnCallCharacteristicValueChanged;
            try
            {
                await _callChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.None);
            }
            catch
            {
                // Ignore cleanup errors
            }
            _callChar = null;
        }

        if (_eventUpChar is not null)
        {
            _eventUpChar.ValueChanged -= OnEventUpCharacteristicValueChanged;
            try
            {
                await _eventUpChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.None);
            }
            catch
            {
                // Ignore cleanup errors
            }
            _eventUpChar = null;
        }

        _eventDownChar = null;
        _commandChar = null;
        _service?.Dispose();
        _service = null;

        if (_gattSession is not null)
        {
            _gattSession.SessionStatusChanged -= OnGattSessionStatusChanged;
            _gattSession.Dispose();
            _gattSession = null;
        }

        if (_device is not null)
        {
            _device.ConnectionStatusChanged -= OnDeviceConnectionStatusChanged;
            _device.Dispose();
            _device = null;
        }

        ConnectionState = BleConnectionState.Disconnected;
        ConnectedDeviceAddress = null;
        CurrentStatus = new HotspotStatus();
        CurrentCall = new CallInfo();
        CallStateChanged?.Invoke(CurrentCall);
        StateChanged?.Invoke();
    }

    private void OnDeviceConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected &&
            ConnectionState == BleConnectionState.Connected)
        {
            TriggerUnexpectedDisconnect("设备连接断开");
        }
    }

    private void OnGattSessionStatusChanged(GattSession sender, GattSessionStatusChangedEventArgs args)
    {
        if (args.Status == GattSessionStatus.Closed &&
            ConnectionState == BleConnectionState.Connected)
        {
            TriggerUnexpectedDisconnect($"GATT 会话已关闭 ({args.Error})");
        }
    }

    private void TriggerUnexpectedDisconnect(string reason)
    {
        if (_disposed || _userRequestedDisconnect) return;
        if (Interlocked.CompareExchange(ref _disconnectHandling, 1, 0) != 0) return;

        Log(reason);
        _ = Task.Run(async () =>
        {
            try
            {
                await _connectLock.WaitAsync().ConfigureAwait(false);
                try
                {
                    if (ConnectionState == BleConnectionState.Connected)
                    {
                        await DisconnectInternalAsync().ConfigureAwait(false);
                    }
                }
                finally
                {
                    _connectLock.Release();
                }

                if (_settings.AutoReconnect && !_userRequestedDisconnect && !_disposed)
                {
                    // Brief backoff so Android finishes restarting BLE advertising
                    await Task.Delay(1200).ConfigureAwait(false);
                    if (ConnectionState == BleConnectionState.Disconnected && !_disposed && !_userRequestedDisconnect)
                    {
                        StartScan();
                    }
                }
            }
            finally
            {
                Interlocked.Exchange(ref _disconnectHandling, 0);
            }
        });
    }

    private void ScheduleReconnectScan(int delayMs)
    {
        _ = Task.Run(async () =>
        {
            await Task.Delay(delayMs).ConfigureAwait(false);
            if (!_disposed && !_userRequestedDisconnect && ConnectionState == BleConnectionState.Disconnected)
            {
                StartScan();
            }
        });
    }

    /// <summary>
    /// Low-overhead fallback sync &amp; link health check:
    /// Relies primarily on GATT Notifications and only issues an uncached Read
    /// if no notification has been received for &gt;= 10 seconds, preventing 2.4GHz contention.
    /// </summary>
    private void StartStatusKeepAlive()
    {
        StopStatusKeepAlive();
        var cts = new CancellationTokenSource();
        _pollCts = cts;
        var token = cts.Token;

        _ = Task.Run(async () =>
        {
            int consecutiveFailures = 0;
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(5000, token).ConfigureAwait(false);
                    var ch = _statusChar;
                    if (ch is null || ConnectionState != BleConnectionState.Connected)
                        continue;

                    if ((DateTime.UtcNow - _lastStatusActivityUtc).TotalSeconds < 10.0)
                    {
                        consecutiveFailures = 0;
                        continue;
                    }

                    var res = await ch.ReadValueAsync(BluetoothCacheMode.Uncached);
                    if (res.Status == GattCommunicationStatus.Success)
                    {
                        consecutiveFailures = 0;
                        _lastStatusActivityUtc = DateTime.UtcNow;
                        var decoded = BleProtocol.DecodeStatus(res.Value.ToArray());
                        if (decoded is not null && decoded != CurrentStatus)
                        {
                            CurrentStatus = decoded;
                            StateChanged?.Invoke();
                        }
                    }
                    else
                    {
                        consecutiveFailures++;
                        if (consecutiveFailures >= 3)
                        {
                            TriggerUnexpectedDisconnect($"GATT 心跳超时 ({res.Status})");
                            break;
                        }
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch
                {
                    consecutiveFailures++;
                    if (consecutiveFailures >= 3)
                    {
                        TriggerUnexpectedDisconnect("GATT 连接异常中断");
                        break;
                    }
                }
            }
        }, token);
    }

    private void StopStatusKeepAlive()
    {
        try
        {
            _pollCts?.Cancel();
            _pollCts?.Dispose();
        }
        catch
        {
            // Ignore
        }
        _pollCts = null;
    }

    private void OnStatusCharacteristicValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        _lastStatusActivityUtc = DateTime.UtcNow;
        if (ApplyStatusBuffer(args.CharacteristicValue))
        {
            StateChanged?.Invoke();
        }
    }

    private void OnCallCharacteristicValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        _lastStatusActivityUtc = DateTime.UtcNow;
        var bytes = args.CharacteristicValue.ToArray();
        var decoded = BleProtocol.DecodeCallInfo(bytes);
        if (decoded is not null)
        {
            CurrentCall = decoded;
            CallStateChanged?.Invoke(decoded);
        }
    }

    private void OnEventUpCharacteristicValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        _lastStatusActivityUtc = DateTime.UtcNow;
        var json = _frameCodec.FeedChunk(args.CharacteristicValue.ToArray());
        if (!string.IsNullOrWhiteSpace(json))
        {
            EventJsonReceived?.Invoke(json);
        }
    }

    public async Task<bool> SendEventJsonAsync(string json)
    {
        if (ConnectionState != BleConnectionState.Connected || _eventDownChar is null)
            return false;

        await _eventWriteLock.WaitAsync().ConfigureAwait(false);
        try
        {
            int maxPdu = _gattSession is not null ? Math.Clamp((int)_gattSession.MaxPduSize - 3, 20, 200) : 20;
            var chunks = _frameCodec.Encode(json, maxPdu);
            if (chunks.Count == 0) return false;

            var writeOption = _eventDownChar.CharacteristicProperties.HasFlag(GattCharacteristicProperties.Write)
                ? GattWriteOption.WriteWithResponse
                : GattWriteOption.WriteWithoutResponse;

            foreach (var chunk in chunks)
            {
                var writer = new DataWriter();
                writer.WriteBytes(chunk);
                var res = await _eventDownChar.WriteValueAsync(writer.DetachBuffer(), writeOption);
                if (res != GattCommunicationStatus.Success)
                {
                    LastError = $"互联事件发送失败: {res}";
                    Log(LastError);
                    return false;
                }
                if (chunks.Count > 1)
                {
                    await Task.Delay(24).ConfigureAwait(false);
                }
            }
            _lastStatusActivityUtc = DateTime.UtcNow;
            return true;
        }
        catch (Exception ex)
        {
            LastError = $"互联事件异常: {ex.Message}";
            Log(LastError);
            return false;
        }
        finally
        {
            _eventWriteLock.Release();
        }
    }

    private bool ApplyStatusBuffer(IBuffer buffer)
    {
        var bytes = buffer.ToArray();
        var decoded = BleProtocol.DecodeStatus(bytes);
        if (decoded is not null && decoded != CurrentStatus)
        {
            CurrentStatus = decoded;
            return true;
        }
        return false;
    }

    public async Task<bool> SendCommandAsync(BleCommand command)
    {
        if (ConnectionState != BleConnectionState.Connected || _commandChar is null)
            return false;

        try
        {
            var writer = new DataWriter();
            writer.WriteByte((byte)command);
            var writeOption = _commandChar.CharacteristicProperties.HasFlag(GattCharacteristicProperties.WriteWithoutResponse)
                ? GattWriteOption.WriteWithoutResponse
                : GattWriteOption.WriteWithResponse;

            var status = await _commandChar.WriteValueAsync(writer.DetachBuffer(), writeOption);
            if (status != GattCommunicationStatus.Success)
            {
                LastError = $"指令发送失败: {status}";
                Log(LastError);
                StateChanged?.Invoke();
                return false;
            }
            _lastStatusActivityUtc = DateTime.UtcNow;
            return true;
        }
        catch (Exception ex)
        {
            LastError = $"指令异常: {ex.Message}";
            Log(LastError);
            StateChanged?.Invoke();
            return false;
        }
    }

    private void Log(string message) => MessageLogged?.Invoke(message);

    public static string FormatMacAddress(ulong address)
    {
        var bytes = BitConverter.GetBytes(address);
        return $"{bytes[5]:X2}:{bytes[4]:X2}:{bytes[3]:X2}:{bytes[2]:X2}:{bytes[1]:X2}:{bytes[0]:X2}";
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        StopScan();
        DisconnectInternalAsync().GetAwaiter().GetResult();
        _connectLock.Dispose();
        _eventWriteLock.Dispose();
    }
}
