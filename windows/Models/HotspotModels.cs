using System.Collections.Concurrent;
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
    string Ssid = "",
    bool IsCharging = false)
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
    public static readonly Guid EventUpCharUuid = new("A0B10005-5C7E-4A3B-9E1D-7F8A9B0C1D2E");
    public static readonly Guid EventDownCharUuid = new("A0B10006-5C7E-4A3B-9E1D-7F8A9B0C1D2E");
    public const byte ProtocolVersion = 0x01;

    public static HotspotStatus? DecodeStatus(byte[]? data)
    {
        if (data is null || data.Length < 6 || data[0] != ProtocolVersion)
            return null;

        byte flags = data[1];
        byte hsState = (byte)(flags & 0x03);
        bool isCharging = (flags & 0x04) != 0;
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

        return new HotspotStatus(enabled, transitioning, bars, netType, battery, ssid, isCharging);
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

public sealed class BleFrameCodec
{
    private int _nextMsgId = 1;

    private sealed class PendingMessage(int totalChunks)
    {
        public int TotalChunks { get; } = totalChunks;
        public DateTime CreatedAtUtc { get; } = DateTime.UtcNow;
        public byte[]?[] Parts { get; } = new byte[]?[totalChunks];
        public int ReceivedCount { get; set; }
    }

    private readonly ConcurrentDictionary<byte, PendingMessage> _pending = new();

    public List<byte[]> Encode(string json, int maxChunkSize = 20)
    {
        var payload = Encoding.UTF8.GetBytes(json);
        int dataPerChunk = Math.Clamp(maxChunkSize - 3, 17, 240);
        int totalChunks = Math.Max(1, (payload.Length + dataPerChunk - 1) / dataPerChunk);
        if (totalChunks > 64) return [];

        int rawId = Interlocked.Increment(ref _nextMsgId) & 0xFF;
        byte msgId = (byte)(rawId == 0 ? 1 : rawId);

        var chunks = new List<byte[]>(totalChunks);
        for (int i = 0; i < totalChunks; i++)
        {
            int start = i * dataPerChunk;
            int len = Math.Max(0, Math.Min(dataPerChunk, payload.Length - start));
            var pkt = new byte[3 + len];
            pkt[0] = msgId;
            pkt[1] = (byte)i;
            pkt[2] = (byte)totalChunks;
            if (len > 0)
            {
                Buffer.BlockCopy(payload, start, pkt, 3, len);
            }
            chunks.Add(pkt);
        }
        return chunks;
    }

    public string? FeedChunk(byte[]? packet)
    {
        if (packet is null || packet.Length < 3) return null;
        byte msgId = packet[0];
        int index = packet[1];
        int total = packet[2];
        if (total <= 0 || total > 64 || index >= total) return null;

        var data = new byte[packet.Length - 3];
        if (data.Length > 0)
        {
            Buffer.BlockCopy(packet, 3, data, 0, data.Length);
        }

        if (total == 1)
        {
            return Encoding.UTF8.GetString(data);
        }

        var now = DateTime.UtcNow;
        foreach (var kv in _pending)
        {
            if ((now - kv.Value.CreatedAtUtc).TotalSeconds > 15)
            {
                _pending.TryRemove(kv.Key, out _);
            }
        }

        var entry = _pending.GetOrAdd(msgId, _ => new PendingMessage(total));
        lock (entry)
        {
            if (entry.TotalChunks != total)
            {
                _pending.TryRemove(msgId, out _);
                return null;
            }
            if (entry.Parts[index] is null)
            {
                entry.Parts[index] = data;
                entry.ReceivedCount++;
            }
            if (entry.ReceivedCount == total)
            {
                _pending.TryRemove(msgId, out _);
                int totalLen = entry.Parts.Sum(p => p?.Length ?? 0);
                var merged = new byte[totalLen];
                int offset = 0;
                foreach (var p in entry.Parts)
                {
                    if (p is not null)
                    {
                        Buffer.BlockCopy(p, 0, merged, offset, p.Length);
                        offset += p.Length;
                    }
                }
                return Encoding.UTF8.GetString(merged);
            }
        }
        return null;
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
