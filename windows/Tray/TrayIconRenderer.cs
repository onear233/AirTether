using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.IO;
using System.Windows.Forms;
using AirTether.Models;

namespace AirTether.Tray;

public static class TrayIconRenderer
{
    // Windows 8.1 authentic signal icon style:
    // 5 ascending vertical bars on a 16x16 base pixel grid (outer width = 4px sharing 1px borders -> 16px total),
    // white interior fill + semi-transparent dark 1px outline, plus Win8.1 red 'x' badge at bottom-right.
    public static Icon RenderSignalIcon(BleConnectionState connectionState, HotspotStatus status)
    {
        bool connected = connectionState == BleConnectionState.Connected;
        // Map Android's 0..4 bars cleanly onto Windows 8.1's 5 bars
        int activeBars = connected
            ? Math.Clamp(status.SignalBars, 0, 4) switch
            {
                0 => 0,
                1 => 1,
                2 => 3,
                3 => 4,
                _ => 5
            }
            : 0;
        bool showCross = !connected || !status.HotspotEnabled;

        return CreateIconFromBitmaps(
            RenderWin81SignalBitmap(activeBars, showCross, 1),
            RenderWin81SignalBitmap(activeBars, showCross, 2));
    }

    private static Bitmap RenderWin81SignalBitmap(int activeBars, bool showCross, int scale)
    {
        int size = 16 * scale;
        var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bmp);
        g.SmoothingMode = SmoothingMode.None;
        g.InterpolationMode = InterpolationMode.NearestNeighbor;
        g.PixelOffsetMode = PixelOffsetMode.None;
        g.CompositingMode = CompositingMode.SourceCopy;
        g.Clear(Color.Transparent);

        // Authentic Win8.1 translucent dark outline (matches 捕获.PNG ~55% black blend over taskbar)
        using var activeBorderBrush = new SolidBrush(Color.FromArgb(165, 0, 0, 0));
        using var inactiveBorderBrush = new SolidBrush(Color.FromArgb(135, 0, 0, 0));
        using var activeFillBrush = new SolidBrush(Color.White);
        using var inactiveFillBrush = new SolidBrush(Color.FromArgb(55, 255, 255, 255));

        // 5 bars matching the exact 16x16 Win8.1 pixel grid:
        // x = i*3 (0,3,6,9,12), outer width = 4 (shared 1px border between bars -> total 16px)
        // inner height = (i+1)*2 (2,4,6,8,10), baseline bottom border at y = 14
        for (int i = 0; i < 5; i++)
        {
            bool isLit = i < activeBars;
            int innerH = (i + 1) * 2;
            int outerH = innerH + 2;
            int outerX = i * 3;
            int outerY = 15 - outerH; // bottom pixel row is y=14

            // Draw outer border rectangle first
            g.FillRectangle(
                isLit ? activeBorderBrush : inactiveBorderBrush,
                outerX * scale,
                outerY * scale,
                4 * scale,
                outerH * scale);
        }

        // Draw crisp inner bar fills on top so shared borders stay intact
        for (int i = 0; i < 5; i++)
        {
            bool isLit = i < activeBars;
            int innerH = (i + 1) * 2;
            int innerX = i * 3 + 1;
            int innerY = 14 - innerH;

            g.FillRectangle(
                isLit ? activeFillBrush : inactiveFillBrush,
                innerX * scale,
                innerY * scale,
                2 * scale,
                innerH * scale);
        }

        // Windows 8.1 style crisp red '×' square badge at bottom-right (x=9..15, y=9..15 on 16x16 grid)
        if (showCross)
        {
            using var badgeBorder = new SolidBrush(Color.FromArgb(245, 0x9E, 0x13, 0x19));
            using var badgeFill = new SolidBrush(Color.FromArgb(255, 0xE8, 0x11, 0x23));
            using var whiteBrush = new SolidBrush(Color.White);

            // 7x7 badge with 1px dark-red border (clipped corners for classic Win8.1 crisp feel)
            int bx = 9;
            int by = 9;
            g.FillRectangle(badgeBorder, bx * scale, by * scale, 7 * scale, 7 * scale);
            g.FillRectangle(badgeFill, (bx + 1) * scale, (by + 1) * scale, 5 * scale, 5 * scale);

            // Crisp 3x3 pixel '×' centered inside the 5x5 red fill: (11,11)..(13,13)
            int[] px = [11, 13, 12, 11, 13];
            int[] py = [11, 11, 12, 13, 13];
            for (int k = 0; k < px.Length; k++)
            {
                g.FillRectangle(whiteBrush, px[k] * scale, py[k] * scale, scale, scale);
            }
        }

        return bmp;
    }

    public static Icon RenderNetworkTextIcon(
        BleConnectionState connectionState,
        HotspotStatus status,
        string? customText)
    {
        return CreateIconFromBitmaps(
            RenderWin81TextBitmap(connectionState, status, customText, 16),
            RenderWin81TextBitmap(connectionState, status, customText, 32));
    }

    private static Bitmap RenderWin81TextBitmap(
        BleConnectionState connectionState,
        HotspotStatus status,
        string? customText,
        int size)
    {
        var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
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
            fgColor = Color.FromArgb(180, 255, 255, 255);
        }
        else if (status.NetworkType == NetworkType.None)
        {
            text = "无";
            fgColor = Color.FromArgb(190, 255, 255, 255);
        }
        else
        {
            text = status.MicroNetworkTag;
            fgColor = Color.White;
        }

        float scaleFactor = size / 32f;
        float fontSize = (text.Length switch
        {
            <= 1 => 25f,
            2 => 22f,
            3 => 14.5f,
            _ => 11.5f
        }) * scaleFactor;

        using var font = new Font("Segoe UI", fontSize, FontStyle.Bold, GraphicsUnit.Pixel);
        using var sf = new StringFormat(StringFormat.GenericTypographic)
        {
            Alignment = StringAlignment.Center,
            LineAlignment = StringAlignment.Center,
            FormatFlags = StringFormatFlags.NoWrap
        };

        // Windows 8.1 style dark outline around white glyph (like the IME "中" icon in 捕获.PNG)
        using var outlineBrush = new SolidBrush(Color.FromArgb(175, 0, 0, 0));
        using var textBrush = new SolidBrush(fgColor);

        float d = Math.Max(1f, scaleFactor);
        PointF[] offsets =
        [
            new(-d, 0), new(d, 0), new(0, -d), new(0, d),
            new(-d, -d), new(d, -d), new(-d, d), new(d, d)
        ];

        foreach (var off in offsets)
        {
            g.DrawString(text, font, outlineBrush, new RectangleF(off.X, off.Y, size, size), sf);
        }
        g.DrawString(text, font, textBrush, new RectangleF(0, 0, size, size), sf);

        return bmp;
    }

    /// <summary>
    /// Packs multi-resolution bitmaps (16x16 &amp; 32x32) into a single Windows .ico stream
    /// so Windows picks the exact DPI-matched pixel-perfect frame without blurry downscaling.
    /// </summary>
    private static Icon CreateIconFromBitmaps(params Bitmap[] bitmaps)
    {
        try
        {
            var entries = new List<(int size, byte[] png)>(bitmaps.Length);
            foreach (var bmp in bitmaps)
            {
                using var pngMs = new MemoryStream();
                bmp.Save(pngMs, ImageFormat.Png);
                entries.Add((bmp.Width, pngMs.ToArray()));
            }

            using var icoMs = new MemoryStream();
            using (var bw = new BinaryWriter(icoMs, System.Text.Encoding.UTF8, leaveOpen: true))
            {
                bw.Write((ushort)0); // reserved
                bw.Write((ushort)1); // type = ICO
                bw.Write((ushort)entries.Count);

                int offset = 6 + 16 * entries.Count;
                foreach (var (sz, png) in entries)
                {
                    byte dim = sz >= 256 ? (byte)0 : (byte)sz;
                    bw.Write(dim);
                    bw.Write(dim);
                    bw.Write((byte)0);
                    bw.Write((byte)0);
                    bw.Write((ushort)1);
                    bw.Write((ushort)32);
                    bw.Write((uint)png.Length);
                    bw.Write((uint)offset);
                    offset += png.Length;
                }

                foreach (var (_, png) in entries)
                {
                    bw.Write(png);
                }
            }

            icoMs.Position = 0;
            using var icon = new Icon(icoMs);
            return (Icon)icon.Clone();
        }
        finally
        {
            foreach (var bmp in bitmaps)
            {
                bmp.Dispose();
            }
        }
    }
}
