using AirTether.Models;

namespace AirTether.Services;

/// <summary>
/// Tracks phone battery level &amp; charging state to fire one-shot Windows native notifications
/// on low battery and full charge (100%).
/// </summary>
public sealed class BatteryAlertService
{
    private readonly AppSettings _settings;
    private readonly IncomingCallNotifier _notifier;

    private bool _lowAlertSent;
    private bool _fullAlertSent;

    public BatteryAlertService(AppSettings settings, IncomingCallNotifier notifier)
    {
        _settings = settings;
        _notifier = notifier;
    }

    public void Reset()
    {
        _lowAlertSent = false;
        _fullAlertSent = false;
    }

    public void HandleStatus(BleConnectionState connectionState, HotspotStatus status, string deviceName)
    {
        if (!_settings.EnableBatteryAlerts || connectionState != BleConnectionState.Connected)
        {
            if (connectionState != BleConnectionState.Connected)
            {
                Reset();
            }
            return;
        }

        int pct = status.BatteryPercent;
        if (pct < 0 || pct > 100) return;

        int lowThreshold = Math.Clamp(_settings.LowBatteryThreshold, 5, 50);

        if (!status.IsCharging && pct <= lowThreshold)
        {
            if (!_lowAlertSent)
            {
                _lowAlertSent = true;
                _notifier.ShowBatteryAlert(
                    $"手机电量低 ({pct}%)",
                    "请及时为手机充电，避免连接中断",
                    deviceName);
            }
        }
        else if (status.IsCharging || pct >= lowThreshold + 5)
        {
            _lowAlertSent = false;
        }

        if (status.IsCharging && pct >= 100)
        {
            if (!_fullAlertSent)
            {
                _fullAlertSent = true;
                _notifier.ShowBatteryAlert(
                    "手机已充满电 (100%)",
                    "可以拔下充电器以保护电池健康",
                    deviceName);
            }
        }
        else if (!status.IsCharging || pct <= 97)
        {
            _fullAlertSent = false;
        }
    }
}
