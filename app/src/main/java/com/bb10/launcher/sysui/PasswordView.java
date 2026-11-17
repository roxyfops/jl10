package com.bb10.launcher.sysui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bb10.launcher.ui.StatusBarView;
import com.bb10.launcher.ui.Ui;

final class PasswordView extends FrameLayout {
    private final TextView title, message;
    private final Field field;
    private EditText stock;
    private TextView[] stockMessages = new TextView[0];

    private final TextWatcher lengthWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void afterTextChanged(Editable s) {
            count(s.length());
        }
    };

    private final TextWatcher messageWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void afterTextChanged(Editable s) {
            copyMessage();
        }
    };

    final float builtScale = SysUi.lockScale, builtBarScale = StatusBarView.scale;

    PasswordView(Context c) {
        super(c);
        float S = KeyguardView.S();
        setClickable(true);
        setFocusable(false);
        setBackgroundColor(0xFF000000);
        wall = new android.widget.ImageView(c);
        wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        addView(wall, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        View dim = new View(c) {
            private final Paint gp = new Paint();

            @Override
            protected void onSizeChanged(int w, int h, int ow, int oh) {
                gp.setShader(new android.graphics.LinearGradient(0, 0, 0, h, new int[]{0xFF000000, 0xFF000000, 0x66000000},
                        new float[]{0f, 0.21f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            }

            @Override
            protected void onDraw(Canvas cv) {
                cv.drawRect(0, 0, getWidth(), getHeight(), gp);
            }
        };
        dim.setWillNotDraw(false);
        addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        int side = Math.round(0.061f * S);
        title = text(c, "Enter device password", 0.072f, Ui.TEXT);
        title.setGravity(Gravity.START);
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tl.leftMargin = side;
        tl.rightMargin = side;
        tl.topMargin = Math.round(0.442f * S + title.getPaint().getFontMetrics().ascent);
        addView(title, tl);

        field = new Field(c);
        LayoutParams fl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.086f * S), Gravity.TOP);
        fl.leftMargin = side;
        fl.rightMargin = side;
        fl.topMargin = Math.round(0.494f * S);
        addView(field, fl);
        field.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_UP && e.getX() > v.getWidth() - 0.09f * S) {
                field.reveal = !field.reveal;
                field.invalidate();
            }
            return true;
        });

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button(c, "Cancel");
        cancel.setOnClickListener(v -> KeyguardHook.cancelPassword());
        ok = button(c, "OK");
        ok.setOnClickListener(v -> submit());
        setOkEnabled(false);
        LinearLayout.LayoutParams bl1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        bl2.leftMargin = Math.round(0.015f * S);
        buttons.addView(cancel, bl1);
        buttons.addView(ok, bl2);
        LayoutParams bll = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.086f * S), Gravity.TOP);
        bll.leftMargin = side;
        bll.rightMargin = side;
        bll.topMargin = Math.round(0.644f * S);
        addView(buttons, bll);

        message = text(c, "", 0.03f, Ui.TEXT2);
        message.setGravity(Gravity.CENTER);
        message.setSingleLine(false);
        message.setMaxLines(2);
        LayoutParams ml = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        ml.leftMargin = side;
        ml.rightMargin = side;
        ml.topMargin = Math.round(0.755f * S);
        addView(message, ml);

        int statusH = StatusBarView.height();
        StatusBarView bar = new StatusBarView(c);
        bar.transparent = true;
        bar.passTouches = true;
        bar.carrierTitle = true;
        bar.rowHeight = statusH;
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusH, Gravity.TOP));
        statusBar = bar;

        LinearLayout emergency = new LinearLayout(c);
        emergency.setOrientation(LinearLayout.HORIZONTAL);
        emergency.setGravity(Gravity.CENTER_VERTICAL);
        int ep = Math.round(0.02f * S);
        emergency.setPadding(ep, 0, Math.round(0.028f * S), 0);
        emergency.setBackground(Ui.selectable(0));
        android.widget.ImageView handset = new android.widget.ImageView(c);
        android.graphics.drawable.Drawable hd = SysUi.res.getDrawable(com.bb10.launcher.R.drawable.ic_phone, null).mutate();
        hd.setTint(0xFFE0201C);
        handset.setImageDrawable(hd);
        int hs = Math.round(0.042f * S);
        emergency.addView(handset, new LinearLayout.LayoutParams(hs, hs));
        TextView et = text(c, "Emergency", 0.032f, Ui.TEXT);
        LinearLayout.LayoutParams etl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etl.leftMargin = Math.round(0.03f * S);
        emergency.addView(et, etl);
        emergency.setOnClickListener(v -> KeyguardHook.emergency());
        LayoutParams eml = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(0.06f * S), Gravity.TOP | Gravity.END);
        eml.topMargin = Math.round(0.101f * S) - Math.round(0.03f * S);
        addView(emergency, eml);
    }

    private TextView ok;

    private void count(int n) {
        field.setCount(n);
        setOkEnabled(n > 0);
    }

    private void setOkEnabled(boolean on) {
        if (ok == null || (ok.isEnabled() == on && ok.getTag() != null)) return;
        ok.setTag(Boolean.TRUE);
        ok.setEnabled(on);
        ok.setTextColor(on ? Ui.TEXT : 0xFF6E6E6E);
    }

    private void submit() {
        EditText f = KeyguardHook.stockField();
        if (f == null) f = stock;
        if (f == null) return;
        KeyguardHook.pollPassword(f);
        f.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    }

    private final StatusBarView statusBar;

    private static TextView text(Context c, String s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(Ui.regular());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * KeyguardView.S());
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static TextView button(Context c, String s) {
        TextView t = text(c, s, 0.036f, Ui.TEXT);
        t.setTypeface(Ui.bold());
        t.setGravity(Gravity.CENTER);
        float r = 0.006f * KeyguardView.S();
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF333333, 0xFF292929});
        g.setCornerRadius(r);
        t.setBackground(g);
        t.setForeground(Ui.selectable(0));
        t.setFocusable(false);
        return t;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            EditText f = KeyguardHook.stockField();
            if (f != null) {
                stock = f;
                KeyguardHook.pollPassword(f);
                count(Math.max(f.length(), KeyguardHook.pendingLength()));
                field.setText(field.reveal ? f.getText().toString() : null);
            }
            copyMessage();
            postDelayed(this, 50);
        }
    };

    void onShown(EditText stockField, TextView[] messages) {
        onHidden();
        animate().cancel();
        setAlpha(1f);
        stock = stockField;
        stockMessages = messages;
        Bitmap wp = KeyguardView.wallpaper();
        if (wp != null) wall.setImageBitmap(wp);
        count(Math.max(stock.length(), KeyguardHook.pendingLength()));
        copyMessage();
        statusBar.start();
        field.startBlink();
        post(poll);
        stock.requestFocus();
    }

    void keyed() {
        EditText f = KeyguardHook.stockField();
        if (f != null) {
            count(f.length());
            field.setText(field.reveal ? f.getText().toString() : null);
        }
    }

    void onHidden() {
        removeCallbacks(poll);
        field.reveal = false;
        field.setText(null);
        stock = null;
        stockMessages = new TextView[0];
        statusBar.stop();
        field.stopBlink();
    }
    private void copyMessage() {
        StringBuilder sb = new StringBuilder();
        for (TextView m : stockMessages) {
            if (m == null || m.getVisibility() != VISIBLE) continue;
            CharSequence s = m.getText();
            if (s == null || s.length() == 0) continue;
            if (s.toString().trim().equalsIgnoreCase("Enter password")) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        message.setText(sb);
        boolean error = sb.toString().toLowerCase(java.util.Locale.ROOT).matches(".*(wrong|incorrect|try again).*");
        message.setTextColor(error ? 0xFFFF6B6B : Ui.TEXT2);
    }

    private final android.widget.ImageView wall;

    static final long SLIDE_MS = 280;
    static final long FADE_MS = SLIDE_MS;

    void goingAway(boolean away) {
        if (away && closing) return;
        animate().cancel();
        closing = away;
        if (away) {
            slideStartedAt = android.os.SystemClock.uptimeMillis();
            animate().translationY(-Math.max(getHeight(), 1)).setStartDelay(0).setDuration(SLIDE_MS)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
        } else {
            setTranslationY(0f);
            setAlpha(1f);
        }
    }

    private boolean closing;
    volatile long slideStartedAt;

    void shake() {
        field.animate().cancel();
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(field, "translationX",
                0, 18, -18, 14, -14, 8, -8, 0);
        a.setDuration(380);
        a.start();
    }

    private static final class Field extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final android.graphics.Path eye = new android.graphics.Path();
        private int count;
        private boolean caret = true, blinking;
        boolean reveal;
        private String text;

        void setText(String t) {
            if (java.util.Objects.equals(t, text)) return;
            text = t;
            invalidate();
        }
        private final Runnable blink = new Runnable() {
            @Override
            public void run() {
                caret = !caret;
                invalidate();
                if (blinking) postDelayed(this, 530);
            }
        };

        Field(Context c) {
            super(c);
        }

        void setCount(int n) {
            if (n == count) return;
            count = n;
            caret = true;
            invalidate();
        }

        void startBlink() {
            blinking = true;
            removeCallbacks(blink);
            postDelayed(blink, 530);
        }

        void stopBlink() {
            blinking = false;
            removeCallbacks(blink);
        }

        @Override
        protected void onDraw(Canvas c) {
            float S = KeyguardView.S(), w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF202020);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, 0.003f * S, 0.003f * S, p);
            float ex = w - 0.044f * S, ey = h / 2f, er = 0.023f * S;
            p.setColor(Ui.TEXT);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, 0.003f * S));
            c.drawCircle(ex, ey, er, p);
            float ew = er * 0.62f, eh = er * 0.42f;
            eye.rewind();
            eye.moveTo(ex - ew, ey);
            eye.quadTo(ex, ey - eh * 1.6f, ex + ew, ey);
            eye.quadTo(ex, ey + eh * 1.6f, ex - ew, ey);
            eye.close();
            c.drawPath(eye, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(ex, ey, er * (reveal ? 0.26f : 0.18f), p);
            float x = 0.025f * S, cy = h / 2f, right = ex - er - 0.02f * S;
            float end;
            if (reveal && text != null) {
                p.setTextSize(0.04f * S);
                p.setTypeface(Ui.regular());
                Paint.FontMetrics fm = p.getFontMetrics();
                String t = text;
                while (t.length() > 1 && x + p.measureText(t) > right) t = t.substring(1);
                c.drawText(t, x, cy - (fm.ascent + fm.descent) / 2f, p);
                end = x + p.measureText(t) + 0.004f * S;
            } else {
                float dot = 0.0055f * S, gap = 0.025f * S;
                int shown = Math.min(count, (int) ((right - x) / gap));
                for (int i = 0; i < shown; i++) c.drawCircle(x + dot + i * gap, cy, dot, p);
                end = x + shown * gap + (shown > 0 ? 0 : dot * 0.2f);
            }
            if (caret) {
                p.setColor(Ui.TEXT);
                c.drawRect(end, cy - 0.022f * S, end + Math.max(2, 0.003f * S), cy + 0.022f * S, p);
            }
        }
    }
}
