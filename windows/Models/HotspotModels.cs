using System.Text;

namespace AirTether.Models;

public enum NetworkType : byte
{
    None = 0,
    Net2G = 1,
    Net3G = 2,
    Net4G = 3,
    Net5G = 4
}

public enum BleCommand : byte
{
    HotspotOff = 0x00,
    HotspotOn = 0x01,
    HotspotToggle = 0x02,
    RefreshStatus = 0x03,
    CallReject = 0x04,
    CallSilence = 0x05
}

public enum CallState : byte
{
    Idle = 0,
    Ringing = 1,
    Offhook = 2
}

public sealed record CallInfo(
    CallState State = CallState.Idle,
    string PhoneNumber = "",
    string ContactName = "");

public enum BleConnectionState
{
    Disconnected,
    Connecting,
    Connected
}

public sealed record HotspotStatus(
    bool HotspotEnabled = false,
    bool IsTransitioning = false,
    int SignalBars = 0,
    NetworkType NetworkType = NetworkType.None,
    int BatteryPercent = -1,
    string Ssid = "")
{
    public string NetworkLabel => NetworkType switch
    {
        NetworkType.Net5G => "5G",
        NetworkType.Net4G => "4G",
        NetworkType.Net3G => "3G",
        NetworkType.Net2G => "2G",
        _ => "无服务"
    };

    public string MicroNetworkTag => NetworkType switch
    {
        NetworkType.Net5G => "5G",
        NetworkType.Net4G => "4G",
        NetworkType.Net3G => "3G",
        NetworkType.Net2G => "2G",
        _ => ""
    };
}

public static class BleProtocol
{
    public static readonly Guid ServiceUuid = new("A0B10001-5C7E-4A3B-9E1D-7F8A9B0C1D2E");
    public static readonly Guid StatusCharUuid = new("A0B10002-5C7E-4A3B-9E1D-7F8A9B0C1D2E");
    public static readonly Guid CommandCharUuid = new("A0B10003-5C7E-4A3B-9E1D-7F8A9B0C1D2E");
    public static readonly Guid CallCharUuid = new("A0B10004-5C7E-4A3B-9E1D-7F8A9B0C1D2E");
    public const byte ProtocolVersion = 0x01;

    public static HotspotStatus? DecodeStatus(byte[]? data)
    {
        if (data is null || data.Length < 6 || data[0] != ProtocolVersion)
            return null;

        byte hsState = data[1];
        bool enabled = hsState == 1 || hsState == 2;
        bool transitioning = hsState == 2;
        int bars = Math.Clamp((int)data[2], 0, 4);
        var netType = Enum.IsDefined(typeof(NetworkType), data[3])
            ? (NetworkType)data[3]
            : NetworkType.None;
        int battery = data[4] <= 100 ? data[4] : -1;
        int ssidLen = data[5];

        string ssid = "";
        if (ssidLen > 0 && data.Length >= 6 + ssidLen)
        {
            ssid = Encoding.UTF8.GetString(data, 6, ssidLen);
        }

        return new HotspotStatus(enabled, transitioning, bars, netType, battery, ssid);
    }

    public static CallInfo? DecodeCallInfo(byte[]? data)
    {
        if (data is null || data.Length < 4 || data[0] != ProtocolVersion)
            return null;

        var state = Enum.IsDefined(typeof(CallState), data[1])
            ? (CallState)data[1]
            : CallState.Idle;

        int numLen = data[2];
        int offset = 3;
        string number = "";
        if (numLen > 0 && data.Length >= offset + numLen)
        {
            number = Encoding.UTF8.GetString(data, offset, numLen);
            offset += numLen;
        }

        string name = "";
        if (data.Length > offset)
        {
            int nameLen = data[offset++];
            if (nameLen > 0 && data.Length >= offset + nameLen)
            {
                name = Encoding.UTF8.GetString(data, offset, nameLen);
            }
        }

        return new CallInfo(state, number, name);
    }
}

public sealed class DiscoveredBleDevice
{
    public ulong Address { get; init; }
    public string FormattedAddress { get; init; } = "";
    public string Name { get; set; } = "未知设备";
    public short Rssi { get; set; }
    public bool HasTargetService { get; set; }
    public DateTime LastSeen { get; set; } = DateTime.UtcNow;

    public string DisplayTitle => string.IsNullOrWhiteSpace(Name) ? FormattedAddress : Name;
    public string Subtitle => $"{FormattedAddress}  ·  {Rssi} dBm";
}
