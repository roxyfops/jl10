package com.bb10.launcher.sysui;

import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.feature.MmTelFeature;

final class ImsWatch {
    static final String KEY = "bb10_volte";
    static volatile String label = "";

    private static int sub = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private static ImsMmTelManager mm;
    private static boolean registered, wifi, voice;
    private static volatile boolean inCall;
    private static boolean callWatch, written;

    private ImsWatch() {}

    private static final ImsMmTelManager.CapabilityCallback caps = new ImsMmTelManager.CapabilityCallback() {
        @Override
        public void onCapabilitiesStatusChanged(MmTelFeature.MmTelCapabilities c) {
            voice = c.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);
            update();
        }
    };

    private static final RegistrationManager.RegistrationCallback reg = new RegistrationManager.RegistrationCallback() {
        @Override
        public void onRegistered(ImsRegistrationAttributes a) {
            registered = true;
            wifi = a.getTransportType() == AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
            update();
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onRegistered(int transport) {
            registered = true;
            wifi = transport == AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
            update();
        }

        @Override
        public void onUnregistered(ImsReasonInfo info) {
            registered = false;
            update();
        }
    };

    static void watch() {
        if (SysUi.sys == null) return;
        watchCalls();
        if (!written) update();
        int s = SubscriptionManager.getDefaultVoiceSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(s)) s = SubscriptionManager.getDefaultSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(s) || s == sub) return;
        try {
            if (mm != null) {
                mm.unregisterMmTelCapabilityCallback(caps);
                mm.unregisterImsRegistrationCallback(reg);
            }
        } catch (Throwable ignored) {
        }
        sub = s;
        registered = wifi = voice = false;
        try {
            mm = SysUi.sys.getSystemService(android.telephony.ims.ImsManager.class).getImsMmTelManager(s);
            mm.registerImsRegistrationCallback(SysUi.sys.getMainExecutor(), reg);
            mm.registerMmTelCapabilityCallback(SysUi.sys.getMainExecutor(), caps);
            SysUi.note("VoLTE watch on SIM subscription " + s);
        } catch (Throwable t) {
            mm = null;
            SysUi.note("VoLTE state unavailable: " + t);
        }
    }

    private static void watchCalls() {
        if (callWatch) return;
        try {
            android.telephony.TelephonyManager tm = SysUi.sys.getSystemService(android.telephony.TelephonyManager.class);
            tm.registerTelephonyCallback(SysUi.sys.getMainExecutor(), new CallState());
            callWatch = true;
        } catch (Throwable t) {
            SysUi.note("call state unavailable (VoLTE shows only in calls): " + t);
        }
    }

    private static final class CallState extends android.telephony.TelephonyCallback
            implements android.telephony.TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            inCall = state != android.telephony.TelephonyManager.CALL_STATE_IDLE;
            update();
        }
    }

    private static void update() {
        String l = inCall && registered && voice ? (wifi ? "VoWiFi" : "VoLTE") : "";
        if (l.equals(label) && written) return;
        written = true;
        label = l;
        SysUi.changed();
        try {
            Settings.Global.putString(SysUi.sys.getContentResolver(), KEY, l);
        } catch (Throwable ignored) {
        }
    }
}
