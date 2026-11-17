package com.bb10.launcher.quickstep;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import com.bb10.launcher.LauncherActivity;

import java.util.HashSet;
import java.util.Set;

public class QuickstepService extends Service {
    private static final String TAG = "BB10Shell";
    private static final String[] DESCRIPTORS = {
            "com.android.systemui.shared.recents.ILauncherProxy",
            "com.android.systemui.shared.recents.IOverviewProxy",
    };
    private static final int ON_OVERVIEW_TOGGLE = IBinder.FIRST_CALL_TRANSACTION + 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Integer> seenCodes = new HashSet<>();
    private String descriptor;

    private final Binder proxy = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(descriptor != null ? descriptor : DESCRIPTORS[0]);
                return true;
            }
            if (!readHeader(data)) {
                if (seenCodes.add(code)) ShellTransitions.note("SystemUI call " + code + " with an unknown interface name");
                return true;
            }
            int payload = data.dataAvail();
            int id = code - FIRST_CALL_TRANSACTION;
            if (seenCodes.add(code) || (id >= 6 && id <= 8))
                ShellTransitions.note("SystemUI call #" + id + " on " + descriptor + " (" + payload + " bytes)");
            if (code == ON_OVERVIEW_TOGGLE && payload <= 8) {
                main.post(QuickstepService.this::showFrames);
                return true;
            }
            if (payload > 0) {
                int pos = data.dataPosition();
                try {
                    Bundle b = data.readTypedObject(Bundle.CREATOR);
                    if (b != null && !b.isEmpty()) {
                        b.size();
                        main.post(() -> ShellTransitions.onSystemUiInit(QuickstepService.this, b));
                        return true;
                    }
                } catch (Throwable ignored) {
                }
                data.setDataPosition(pos);
            }
            return true;
        }
    };

    private boolean readHeader(Parcel data) {
        for (String d : DESCRIPTORS) {
            data.setDataPosition(0);
            try {
                data.enforceInterface(d);
                descriptor = d;
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void showFrames() {
        if (com.bb10.launcher.lock.LockActivity.isShowing()) return;
        com.bb10.launcher.gesture.GestureService.recentsPressed(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        ShellTransitions.note("SystemUI connected to the QuickStep service");
        return proxy;
    }
}
