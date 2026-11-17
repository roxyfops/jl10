package com.bb10.launcher.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

public final class Dialogs {
    public interface TextCallback {
        void done(String s);
    }

    private Dialogs() {}

    public static void input(Context c, String title, String initial, String hint, boolean allowReset, TextCallback cb) {
        EditText e = new EditText(c);
        e.setSingleLine();
        e.setText(initial);
        e.setHint(hint);
        e.setSelectAllOnFocus(true);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        FrameLayout f = new FrameLayout(c);
        f.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        f.addView(e);
        AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(title)
                .setView(f)
                .setPositiveButton("Save", (d, w) -> cb.done(e.getText().toString().trim()))
                .setNegativeButton("Cancel", null);
        if (allowReset) b.setNeutralButton("Reset", (d, w) -> cb.done(null));
        AlertDialog d = b.create();
        e.setOnEditorActionListener((v, a, ev) -> {
            cb.done(e.getText().toString().trim());
            d.dismiss();
            return true;
        });
        d.show();
        e.requestFocus();
    }

    public static void confirm(Context c, String title, String msg, String ok, Runnable r) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(ok, (d, w) -> r.run())
                .setNegativeButton("Cancel", null)
                .show();
    }
}
