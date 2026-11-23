package com.bthotspot.android.hotspot;

interface IHotspotUserService {
    void destroy() = 16777114;
    boolean setHotspotEnabled(boolean enabled) = 1;
    String getLastError() = 2;
    boolean endCall() = 3;
    boolean silenceRinger() = 4;
    String getHotspotSsid() = 5;
}
