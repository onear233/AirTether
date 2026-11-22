using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Security;
using AirTether.Models;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;

namespace AirTether.Services;

public sealed class IncomingCallNotifier
{
    private const string AppId = "AirTether.App";
    private const string ToastTag = "incoming_call";
    private const string ToastGroup = "calls";

    private readonly BleHotspotClient _client;
    private readonly string _avatarPath;
    private bool _toastRegistered;
    private string _lastRingingNumber = "";

    public IncomingCallNotifier(BleHotspotClient client)
    {
        _client = client;
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "AirTether");
        Directory.CreateDirectory(dir);
        _avatarPath = Path.Combine(dir, "default_avatar.png");

        EnsureDefaultAvatar(_avatarPath);
        _toastRegistered = EnsureStartMenuShortcut();
    }

    public void HandleCallState(CallInfo call, string deviceName)
    {
        if (call.State == CallState.Ringing)
        {
            string number = string.IsNullOrWhiteSpace(call.PhoneNumber) ? "未知号码" : call.PhoneNumber;
            if (number == _lastRingingNumber)
            {
                return;
            }
            _lastRingingNumber = number;
            ShowIncomingCallToast(number, call.ContactName, deviceName);
        }
        else
        {
            _lastRingingNumber = "";
            DismissCallToast();
        }
    }

    private void ShowIncomingCallToast(string phoneNumber, string contactName, string deviceName)
    {
        if (!_toastRegistered)
        {
            _toastRegistered = EnsureStartMenuShortcut();
        }

        try
        {
            string title = string.IsNullOrWhiteSpace(contactName) ? "手机来电" : SecurityElement.Escape(contactName);
            string escapedNumber = SecurityElement.Escape(phoneNumber);
            string escapedDevice = SecurityElement.Escape(string.IsNullOrWhiteSpace(deviceName) ? "手机" : deviceName);
            string avatarUri = new Uri(_avatarPath).AbsoluteUri;

            string xmlString = $"""
                <toast scenario="incomingCall">
                    <visual>
                        <binding template="ToastGeneric">
                            <text>{title}</text>
                            <text>{escapedNumber}</text>
                            <text placement="attribution">来自 {escapedDevice}</text>
                            <image placement="appLogoOverride" hint-crop="circle" src="{avatarUri}"/>
                        </binding>
                    </visual>
                    <audio src="ms-winsoundevent:Notification.Looping.Call" loop="true"/>
                    <actions>
                        <action content="拒接" arguments="action=reject" activationType="foreground"/>
                        <action content="静音" arguments="action=silence" activationType="foreground"/>
                    </actions>
                </toast>
                """;

            var xmlDoc = new XmlDocument();
            xmlDoc.LoadXml(xmlString);

            var toast = new ToastNotification(xmlDoc)
            {
                Tag = ToastTag,
                Group = ToastGroup
            };

            toast.Activated += async (_, args) =>
            {
                if (args is ToastActivatedEventArgs activatedArgs)
                {
                    if (activatedArgs.Arguments.Contains("action=reject", StringComparison.Ordinal))
                    {
                        await _client.SendCommandAsync(BleCommand.CallReject);
                        DismissCallToast();
                    }
                    else if (activatedArgs.Arguments.Contains("action=silence", StringComparison.Ordinal))
                    {
                        await _client.SendCommandAsync(BleCommand.CallSilence);
                    }
                }
            };

            ToastNotificationManager.CreateToastNotifier(AppId).Show(toast);
        }
        catch
        {
            // Ignore toast errors if notifications are disabled by OS policy
        }
    }

    public void DismissCallToast()
    {
        try
        {
            ToastNotificationManager.History.Remove(ToastTag, ToastGroup, AppId);
        }
        catch
        {
            // Ignore
        }
    }

    private static void EnsureDefaultAvatar(string path)
    {
        if (File.Exists(path)) return;

        const int size = 128;
        using var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(bmp);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Color.Transparent);

        // Windows Fluent / Win10 default slate-blue circular contact avatar
        using var bgBrush = new SolidBrush(Color.FromArgb(255, 0x2B, 0x6C, 0xB0));
        g.FillEllipse(bgBrush, 2, 2, size - 4, size - 4);

        using var fgBrush = new SolidBrush(Color.FromArgb(245, 255, 255, 255));
        // Head
        g.FillEllipse(fgBrush, 44, 28, 40, 40);
        // Shoulders (clipped inside circle)
        using var clipPath = new GraphicsPath();
        clipPath.AddEllipse(2, 2, size - 4, size - 4);
        g.SetClip(clipPath);
        g.FillEllipse(fgBrush, 24, 76, 80, 64);
        g.ResetClip();

        bmp.Save(path, ImageFormat.Png);
    }

    private static bool EnsureStartMenuShortcut()
    {
        try
        {
            SetCurrentProcessExplicitAppUserModelID(AppId);

            string? exePath = Environment.ProcessPath;
            if (string.IsNullOrWhiteSpace(exePath)) return false;

            string shortcutPath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "Microsoft", "Windows", "Start Menu", "Programs", "AirTether.lnk");

            var shellLink = (IShellLinkW)new CShellLink();
            shellLink.SetPath(exePath);
            shellLink.SetWorkingDirectory(Path.GetDirectoryName(exePath) ?? "");
            shellLink.SetIconLocation(exePath, 0);

            var propStore = (IPropertyStore)shellLink;
            var key = new PropertyKey(new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), 5); // System.AppUserModel.ID
            var propVar = new PropVariant(AppId);
            try
            {
                propStore.SetValue(ref key, ref propVar);
                propStore.Commit();
            }
            finally
            {
                propVar.Dispose();
            }

            var persistFile = (IPersistFile)shellLink;
            persistFile.Save(shortcutPath, true);
            return true;
        }
        catch
        {
            return false;
        }
    }

    [DllImport("shell32.dll", SetLastError = true)]
    private static extern int SetCurrentProcessExplicitAppUserModelID([MarshalAs(UnmanagedType.LPWStr)] string appId);

    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    private class CShellLink { }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
    private interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszFile, int cchMaxPath, IntPtr pfd, uint fFlags);
        void GetIDList(out IntPtr ppidl);
        void SetIDList(IntPtr pidl);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszName, int cchMaxName);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszDir, int cchMaxPath);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszArgs, int cchMaxPath);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
        void GetHotkey(out short pwHotkey);
        void SetHotkey(short wHotkey);
        void GetShowCmd(out int piShowCmd);
        void SetShowCmd(int iShowCmd);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszIconPath, int cchIconPath, out int piIcon);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, uint dwReserved);
        void Resolve(IntPtr hwnd, uint fFlags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
    }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")]
    private interface IPropertyStore
    {
        void GetCount(out uint cProps);
        void GetAt(uint iProp, out PropertyKey pkey);
        void GetValue(ref PropertyKey key, out PropVariant pv);
        void SetValue(ref PropertyKey key, ref PropVariant pv);
        void Commit();
    }

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    private struct PropertyKey
    {
        public Guid fmtid;
        public uint pid;

        public PropertyKey(Guid fmtid, uint pid)
        {
            this.fmtid = fmtid;
            this.pid = pid;
        }
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct PropVariant : IDisposable
    {
        [FieldOffset(0)] private ushort vt;
        [FieldOffset(8)] private IntPtr pwszVal;

        public PropVariant(string value)
        {
            vt = 31; // VT_LPWSTR
            pwszVal = Marshal.StringToCoTaskMemUni(value);
        }

        public void Dispose()
        {
            if (pwszVal != IntPtr.Zero)
            {
                Marshal.FreeCoTaskMem(pwszVal);
                pwszVal = IntPtr.Zero;
            }
        }
    }
}
