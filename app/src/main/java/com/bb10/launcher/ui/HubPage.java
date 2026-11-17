package com.bb10.launcher.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.bb10.launcher.hub.HubItem;

public class HubPage extends FrameLayout {
    public interface Host {
        ViewGroup overlays();

        Drawable iconForPackage(String pkg);

        void launchPackage(String pkg, View src);

        void openCompose();

        void openSettings();

        void requestNotificationAccess();

        void onHubItemOpened();
    }

    public static final float BAR_H = 0.125f;

    private final HubUi ui;

    public HubPage(Context a, Host host) {
        this(a, host, true);
    }

    public HubPage(Context a, Host host, boolean ownBar) {
        super(a);
        ui = new HubUi(a, host, ownBar);
        addView(ui.getListView(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public View actionBar() {
        return ui.getBarView();
    }

    public View composeButton() {
        View v = new View(getContext());
        v.setVisibility(GONE);
        return v;
    }

    public void refresh() {
        ui.refresh();
    }

    public void closeMail() {
        ui.closeMail();
    }

    public boolean onBack() {
        return ui.onBack();
    }

    public boolean onKey(int code, KeyEvent e) {
        return ui.onKey(code, e);
    }

    public void openAccounts() {
        ui.openAccounts();
    }

    public void dragAccounts(float px) {
        ui.dragAccounts(px);
    }

    public float accountsPulled() {
        return ui.accountsPulled();
    }

    public void releaseAccounts(float px, float velocity) {
        ui.releaseAccounts(px, velocity);
    }

    public void showMenu() {
        ui.showMenu();
    }

    public void showSearch() {
        ui.showSearch();
    }

    public void open(HubItem it) {
        ui.open(it);
    }
}
