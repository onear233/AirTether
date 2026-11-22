using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using AirTether.Models;

namespace AirTether.Tray;

public static class TrayIconRenderer
{
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr hIcon);

    public static Icon RenderSignalIcon(BleConnectionState connectionState, HotspotStatus status)
    {
        const int size = 32;
        using var bmp = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bmp);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Color.Transparent);

        bool connected = connectionState == BleConnectionState.Connected;
        int activeBars = connected ? Math.Clamp(status.SignalBars, 0, 4) : 0;
        bool showCross = !connected || !status.HotspotEnabled;

        // Windows 10 style 4 ascending cellular bars
        int[] barX = [2, 10, 18, 26];
        const int barWidth = 4;
        int[] barHeights = [7, 13, 19, 25];
        const int baselineY = 28;

        using var activeBrush = new SolidBrush(Color.White);
        using var inactiveBrush = new SolidBrush(Color.FromArgb(95, 255, 255, 255));

        for (int i = 0; i < 4; i++)
        {
            int h = barHeights[i];
            int y = baselineY - h;
            var rect = new Rectangle(barX[i], y, barWidth, h);
            g.FillRectangle(i < activeBars ? activeBrush : inactiveBrush, rect);
        }

        // Bottom-right Windows 10 style "×" badge when disconnected or hotspot is off
        if (showCross)
        {
            var badgeRect = new Rectangle(18, 18, 13, 13);
            using var badgeBg = new SolidBrush(Color.FromArgb(240, 210, 40, 45));
            using var badgeBorder = new Pen(Color.FromArgb(230, 24, 24, 24), 1.2f);
            g.FillEllipse(badgeBg, badgeRect);
            g.DrawEllipse(badgeBorder, badgeRect);

            using var xPen = new Pen(Color.White, 1.8f)
            {
                StartCap = LineCap.Round,
                EndCap = LineCap.Round
            };
            g.DrawLine(xPen, 22f, 22f, 27f, 27f);
            g.DrawLine(xPen, 27f, 22f, 22f, 27f);
        }

        return CreateIconFromBitmap(bmp);
    }

    public static Icon RenderNetworkTextIcon(
        BleConnectionState connectionState,
        HotspotStatus status,
        string? customText)
    {
        const int size = 32;
        using var bmp = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bmp);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        g.Clear(Color.Transparent);

        bool hasCustom = !string.IsNullOrWhiteSpace(customText);
        bool connected = connectionState == BleConnectionState.Connected;

        string text;
        Color fgColor;
        if (hasCustom)
        {
            text = customText!.Trim();
            fgColor = Color.White;
        }
        else if (!connected)
        {
            text = "--";
            fgColor = Color.FromArgb(160, 255, 255, 255);
        }
        else if (status.NetworkType == NetworkType.None)
        {
            text = "无";
            fgColor = Color.FromArgb(180, 255, 255, 255);
        }
        else
        {
            text = status.MicroNetworkTag;
            fgColor = Color.White;
        }

        float fontSize = text.Length switch
        {
            <= 1 => 28f,
            2 => 26f,
            3 => 15f,
            _ => 12f
        };

        using var font = new Font("Segoe UI", fontSize, FontStyle.Bold, GraphicsUnit.Pixel);
        using var sf = new StringFormat(StringFormat.GenericTypographic)
        {
            Alignment = StringAlignment.Center,
            LineAlignment = StringAlignment.Center,
            FormatFlags = StringFormatFlags.NoWrap
        };

        var rect = new RectangleF(0, 0, size, size);
        using var shadowBrush = new SolidBrush(Color.FromArgb(170, 0, 0, 0));
        using var textBrush = new SolidBrush(fgColor);

        g.DrawString(text, font, shadowBrush, new RectangleF(1, 1, size, size), sf);
        g.DrawString(text, font, textBrush, rect, sf);

        return CreateIconFromBitmap(bmp);
    }

    private static Icon CreateIconFromBitmap(Bitmap bmp)
    {
        IntPtr hIcon = bmp.GetHicon();
        try
        {
            using var tempIcon = Icon.FromHandle(hIcon);
            return (Icon)tempIcon.Clone();
        }
        finally
        {
            DestroyIcon(hIcon);
        }
    }
}
