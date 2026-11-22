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
    private CancellationTokenSource? _pollCts;
    private bool _disposed;

    public event Action? StateChanged;
    public event Action? DevicesUpdated;
    public event Action<CallInfo>? CallStateChanged;
    public event Action<string>? MessageLogged;

    public BleConnectionState ConnectionState { get; private set; } = BleConnectionState.Disconnected;
    public HotspotStatus CurrentStatus { get; private set; } = new();
    public CallInfo CurrentCall { get; private set; } = new();
    public string ConnectedDeviceName { get; private set; } = "";
    public ulong? ConnectedDeviceAddress { get; private set; }
    public bool IsScanning { get; private set; }
    public string LastError { get; private set; } = "";

    public BleHotspotClient(AppSettings settings)
    {
        _settings = settings;
    }

    public IReadOnlyList<DiscoveredBleDevice> GetTargetDevices()
    {
        return _seenDevices.Values
            .Where(d => d.HasTargetService)
            .OrderByDescending(d => d.Address == _settings.LastDeviceAddress)
            .ThenByDescending(d => d.Rssi)
            .ToList();
    }

    public void StartScan()
    {
        if (_disposed || IsScanning) return;

        try
        {
            _watcher = new BluetoothLEAdvertisementWatcher
            {
                ScanningMode = BluetoothLEScanningMode.Active
            };
            _watcher.Received += OnAdvertisementReceived;
            _watcher.Stopped += OnWatcherStopped;
            _watcher.Start();
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
        if (!IsScanning || _watcher is null) return;
        try
        {
            _watcher.Stop();
        }
        catch
        {
            // Ignore
        }
        IsScanning = false;
        StateChanged?.Invoke();
    }

    private void OnWatcherStopped(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementWatcherStoppedEventArgs args)
    {
        IsScanning = false;
        if (args.Error != BluetoothError.Success)
        {
            LastError = $"蓝牙扫描停止: {args.Error}";
        }
        StateChanged?.Invoke();
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

        if (entry.HasTargetService)
        {
            DevicesUpdated?.Invoke();

            if (_settings.AutoReconnect &&
                ConnectionState == BleConnectionState.Disconnected &&
                _settings.LastDeviceAddress == addr)
            {
                _ = ConnectAsync(addr, entry.DisplayTitle);
            }
        }
    }

    public async Task ConnectAsync(ulong address, string? displayName = null)
    {
        if (_disposed) return;
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

            ConnectionState = BleConnectionState.Connected;
            _settings.LastDeviceAddress = address;
            _settings.LastDeviceName = ConnectedDeviceName;
            _settings.Save();

            StopScan();
            StartStatusPolling();
            Log($"已连接 {ConnectedDeviceName}");
            StateChanged?.Invoke();
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            Log($"连接失败: {ex.Message}");
            await DisconnectInternalAsync().ConfigureAwait(false);
        }
        finally
        {
            _connectLock.Release();
        }
    }

    public async Task DisconnectAsync()
    {
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
        StopStatusPolling();

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

        _commandChar = null;
        _service?.Dispose();
        _service = null;

        _gattSession?.Dispose();
        _gattSession = null;

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
            Log("设备已断开连接");
            _ = Task.Run(async () =>
            {
                await _connectLock.WaitAsync().ConfigureAwait(false);
                try
                {
                    await DisconnectInternalAsync().ConfigureAwait(false);
                    if (_settings.AutoReconnect)
                    {
                        StartScan();
                    }
                }
                finally
                {
                    _connectLock.Release();
                }
            });
        }
    }

    private void StartStatusPolling()
    {
        StopStatusPolling();
        var cts = new CancellationTokenSource();
        _pollCts = cts;
        var token = cts.Token;

        _ = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(2000, token).ConfigureAwait(false);
                    var ch = _statusChar;
                    if (ch is null || ConnectionState != BleConnectionState.Connected)
                        continue;

                    var res = await ch.ReadValueAsync(BluetoothCacheMode.Uncached);
                    if (res.Status == GattCommunicationStatus.Success)
                    {
                        var decoded = BleProtocol.DecodeStatus(res.Value.ToArray());
                        if (decoded is not null && decoded != CurrentStatus)
                        {
                            CurrentStatus = decoded;
                            StateChanged?.Invoke();
                        }
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch
                {
                    // Ignore transient GATT read errors
                }
            }
        }, token);
    }

    private void StopStatusPolling()
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
        if (ApplyStatusBuffer(args.CharacteristicValue))
        {
            StateChanged?.Invoke();
        }
    }

    private void OnCallCharacteristicValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var bytes = args.CharacteristicValue.ToArray();
        var decoded = BleProtocol.DecodeCallInfo(bytes);
        if (decoded is not null)
        {
            CurrentCall = decoded;
            CallStateChanged?.Invoke(decoded);
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
    }
}
