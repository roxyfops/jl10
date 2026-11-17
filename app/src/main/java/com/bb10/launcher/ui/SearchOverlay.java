package com.bb10.launcher.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.R;
import com.bb10.launcher.hub.HubItem;
import com.bb10.launcher.model.AppInfo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SearchOverlay extends FrameLayout implements Closable {
    public interface Host {
        void launchApp(String key, View src);

        void startActivity(Intent i);

        void requestContactsPermission();

        void closeSearch(SearchOverlay s);

        default void searchShown(boolean shown) {}

        default boolean fullScreen() { return false; }
    }

    private static final int HILITE = 0xFF1EA5D4;
    private static final int SELECTED = 0xFF024A66;
    private static final int HEADER_TEXT = 0xFFAAAAAA, GREY = 0xFF9A9A9A, GLYPH = 0xFFE7E7E7;
    private static final int PREVIEW = 2;

    private static final String ALL = null, ACTIONS = "actions", APPS = "apps", CONTACTS = "contacts", MESSAGES = "messages", EXTEND = "extend";

    private static final float SIDE = 0.014f, FIELD_TOP = 0.021f, FIELD_H = 0.079f, LIST_TOP = 0.029f;
    private static final float ROW = 0.107f, HEADER = 0.057f, STRIP = 0.076f, CORNER = 0.147f, MIC = 0.128f;

    private final Host act;
    private final EditText field;
    private final ImageView clear;
    private final LinearLayout results, strip;
    private final ScrollView sv;
    private final java.util.Map<String, View> anchors = new java.util.HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable update = this::update;
    private Runnable first;
    private View firstRow;
    private int generation;
    private String filter = ALL;
    private final Set<String> expanded = new HashSet<>();
    private final float S = Ui.S();

    public SearchOverlay(Context a, Host host) {
        super(a);
        act = host;
        setBackgroundColor(0xD9000000);
        setClickable(true);

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        FrameLayout box = new FrameLayout(a);
        box.setBackgroundColor(0xFF202020);
        field = new EditText(a);
        field.setBackground(null);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(0xFF6E6E6E);
        field.setHint("Search");
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.042f * S);
        field.setTypeface(Ui.regular());
        field.setSingleLine();
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        field.setPadding(Math.round(0.021f * S), 0, Math.round(0.08f * S), 0);
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) {
                expanded.clear();
                clear.setVisibility(s.length() > 0 ? VISIBLE : GONE);
                h.removeCallbacks(update);
                h.postDelayed(update, 90);
            }
        });
        field.setOnEditorActionListener((v, id, ev) -> {
            runFirst();
            return true;
        });
        field.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                runFirst();
                return true;
            }
            if (code == KeyEvent.KEYCODE_ESCAPE) {
                close();
                return true;
            }
            if (code == KeyEvent.KEYCODE_CALL) {
                callFirst();
                return true;
            }
            return false;
        });
        box.addView(field, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        clear = new ImageView(a);
        clear.setImageDrawable(Ui.glyph(a, R.drawable.ic_close, Ui.TEXT));
        int cp = Math.round(0.013f * S);
        clear.setPadding(cp, cp, cp, cp);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Math.max(2, Math.round(0.0035f * S)), Ui.TEXT);
        clear.setBackground(ring);
        clear.setContentDescription("Clear");
        clear.setVisibility(GONE);
        clear.setOnClickListener(v -> field.setText(""));
        int cs = Math.round(0.053f * S);
        LayoutParams cl = new LayoutParams(cs, cs, Gravity.END | Gravity.CENTER_VERTICAL);
        cl.rightMargin = Math.round(0.018f * S);
        box.addView(clear, cl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.round(FIELD_H * S));
        bl.leftMargin = bl.rightMargin = Math.round(SIDE * S);
        bl.topMargin = Math.round(FIELD_TOP * S);
        column.addView(box, bl);

        LinearLayout middle = new LinearLayout(a);
        sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, 0, 0, Math.round((CORNER + 0.02f) * S));
        sv.addView(results);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        svl.leftMargin = Math.round(SIDE * S);
        middle.addView(sv, svl);
        strip = new LinearLayout(a) {
            @Override
            public boolean onInterceptTouchEvent(android.view.MotionEvent e) { return true; }

            @Override
            public boolean onTouchEvent(android.view.MotionEvent e) { return stripTouch(e); }
        };
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setGravity(Gravity.CENTER_HORIZONTAL);
        strip.setPadding(0, Math.round(0.002f * S), 0, 0);
        addCategory(ACTIONS, R.drawable.ic_s_action, "Instant Actions");
        addCategory(APPS, R.drawable.ic_s_apps, "Applications");
        addCategory(CONTACTS, R.drawable.ph_contact, "Contacts");
        addCategory(MESSAGES, R.drawable.ic_mail, "Messages");
        addCategory(EXTEND, R.drawable.ic_s_globe, "Extend Search");
        middle.addView(strip, new LinearLayout.LayoutParams(Math.round(STRIP * S), LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        ml.topMargin = Math.round(LIST_TOP * S);
        column.addView(middle, ml);

        int corner = Math.round(CORNER * S);
        View back = cornerButton(a, R.drawable.ic_s_back, 0.042f, "Back");
        back.setOnClickListener(v -> close());
        addView(back, new LayoutParams(corner, corner, Gravity.BOTTOM | Gravity.START));
        View info = cornerButton(a, R.drawable.ic_info, 0.062f, "Search settings");
        info.setOnClickListener(v -> start(new Intent().setClassName(getContext(), "com.bb10.launcher.settings.SettingsActivity")));
        addView(info, new LayoutParams(corner, corner, Gravity.BOTTOM | Gravity.END));
        ImageView mic = new ImageView(a);
        mic.setImageDrawable(Ui.glyph(a, R.drawable.ic_mic, Ui.TEXT));
        int mp = Math.round(0.034f * S);
        mic.setPadding(mp, mp, mp, mp);
        GradientDrawable micBg = new GradientDrawable();
        micBg.setShape(GradientDrawable.OVAL);
        micBg.setColor(0xFF000000);
        micBg.setStroke(Math.max(2, Math.round(0.0042f * S)), 0xFFF0F0F0);
        mic.setBackground(micBg);
        mic.setContentDescription("Voice search");
        mic.setOnClickListener(v -> voice());
        int ms = Math.round(MIC * S);
        LayoutParams mlp = new LayoutParams(ms, ms, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mlp.bottomMargin = Math.round((0.078f - MIC / 2) * S);
        addView(mic, mlp);
        mic.setTranslationX(0.014f * S);
    }

    private View cornerButton(Context a, int icon, float glyph, String desc) {
        ImageView b = new ImageView(a);
        b.setImageDrawable(Ui.glyph(a, icon, Ui.TEXT));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Math.round((CORNER - glyph) / 2f * S);
        b.setPadding(p, p, p, p);
        b.setBackgroundColor(0xFF282828);
        b.setContentDescription(desc);
        return b;
    }

    private void addCategory(String cat, int icon, String desc) {
        ImageView iv = new ImageView(getContext());
        iv.setImageDrawable(Ui.glyph(getContext(), icon, GLYPH));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Math.round(0.0055f * S);
        iv.setPadding(p, p, p, p);
        iv.setTag(cat);
        iv.setContentDescription(desc);
        stripIcons.put(cat, icon);
        strip.addView(iv, new LinearLayout.LayoutParams(Math.round(0.044f * S), Math.round(0.044f * S)));
    }

    private final java.util.Map<String, Integer> stripIcons = new java.util.HashMap<>();
    private FrameLayout bubble;
    private ImageView bubbleGlyph;
    private int stripAt = -1;
    private float stripDownY;
    private boolean stripSlid;

    private boolean stripTouch(android.view.MotionEvent e) {
        int i = stripIndex(e.getY());
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                stripDownY = e.getY();
                stripSlid = false;
                stripAt = -1;
                stripOn(i);
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getY() - stripDownY) > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) stripSlid = true;
                stripOn(i);
                return true;
            case android.view.MotionEvent.ACTION_UP:
                if (bubble != null) bubble.setVisibility(GONE);
                if (!stripSlid && i >= 0) {
                    String cat = (String) strip.getChildAt(i).getTag();
                    filter = cat.equals(filter) ? ALL : cat;
                    update();
                }
                stripAt = -1;
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                if (bubble != null) bubble.setVisibility(GONE);
                stripAt = -1;
                return true;
        }
        return true;
    }

    private int stripIndex(float y) {
        int n = strip.getChildCount();
        if (n == 0) return -1;
        for (int i = 0; i < n; i++) if (y < strip.getChildAt(i).getBottom()) return i;
        return n - 1;
    }

    private void stripOn(int i) {
        if (i < 0 || i == stripAt) return;
        stripAt = i;
        View g = strip.getChildAt(i);
        String cat = (String) g.getTag();
        showBubble(g, stripIcons.get(cat));
        jumpTo(cat);
        if (stripSlid) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    private void showBubble(View glyph, int icon) {
        if (bubble == null) {
            bubble = new FrameLayout(getContext());
            bubble.setBackgroundColor(0xFF323232);
            bubbleGlyph = new ImageView(getContext());
            bubbleGlyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int gs = Math.round(0.097f * S);
            LayoutParams gl = new LayoutParams(gs, gs, Gravity.CENTER_VERTICAL | Gravity.START);
            gl.leftMargin = Math.round((0.092f - 0.0485f) * S);
            bubble.addView(bubbleGlyph, gl);
            View menu = new View(getContext()) {
                final android.graphics.Paint bp = new android.graphics.Paint();

                @Override
                protected void onDraw(android.graphics.Canvas c) {
                    bp.setColor(0xFFF0F0F0);
                    float bar = Math.max(2f, 0.0045f * S), gap = 0.0167f * S, cy = getHeight() / 2f;
                    for (int k = -1; k <= 1; k++) c.drawRect(0, cy + k * gap - bar / 2, getWidth(), cy + k * gap + bar / 2, bp);
                }
            };
            LayoutParams mlp = new LayoutParams(Math.round(0.049f * S), Math.round(0.06f * S), Gravity.CENTER_VERTICAL | Gravity.END);
            mlp.rightMargin = Math.round(0.0255f * S);
            bubble.addView(menu, mlp);
            LayoutParams bl = new LayoutParams(Math.round(0.394f * S), Math.round(0.136f * S), Gravity.TOP | Gravity.END);
            bl.rightMargin = Math.round(SIDE * S);
            addView(bubble, bl);
        }
        bubbleGlyph.setImageDrawable(Ui.glyph(getContext(), icon, 0xFFE0E0E0));
        bubble.setVisibility(VISIBLE);
        android.graphics.Rect r = new android.graphics.Rect();
        glyph.getDrawingRect(r);
        offsetDescendantRectToMyCoords(glyph, r);
        bubble.setTranslationY(r.centerY() - 0.068f * S);
    }

    private void jumpTo(String cat) {
        View a = anchors.get(cat);
        if (a == null || !a.isAttachedToWindow()) return;
        android.graphics.Rect r = new android.graphics.Rect();
        a.getDrawingRect(r);
        results.offsetDescendantRectToMyCoords(a, r);
        sv.smoothScrollTo(0, r.top);
    }

    private void markCategory() {
        for (int i = 0; i < strip.getChildCount(); i++) {
            ImageView iv = (ImageView) strip.getChildAt(i);
            iv.getDrawable().setTint(iv.getTag().equals(filter) ? HILITE : GLYPH);
        }
    }

    public void setImeInset(int px) {
        setPadding(0, 0, 0, px);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (act.fullScreen() && getParent() instanceof ViewGroup && getLayoutParams() instanceof MarginLayoutParams) {
            ((MarginLayoutParams) getLayoutParams()).topMargin = -((ViewGroup) getParent()).getPaddingTop();
            requestLayout();
        }
        act.searchShown(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        act.searchShown(false);
    }

    public void setQuery(String q) {
        field.setText(q);
        field.setSelection(q.length());
        field.requestFocus();
        if (q.isEmpty()) {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) field.post(() -> imm.showSoftInput(field, 0));
        }
        update();
    }

    private boolean shows(String cat) {
        return filter == null || filter.equals(cat);
    }

    private void update() {
        h.removeCallbacks(update);
        final int gen = ++generation;
        String q = field.getText().toString().trim();
        results.removeAllViews();
        anchors.clear();
        first = null;
        firstRow = null;
        firstNumber = null;
        markCategory();
        Context c = getContext();
        if (q.isEmpty()) {
            TextView t = Ui.text(c, "Search apps, contacts and messages, or type an Instant Action.", 0, GREY, false);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.03f * S);
            t.setPadding(Math.round(0.02f * S), Math.round(0.02f * S), Math.round(0.02f * S), 0);
            results.addView(t);
            return;
        }

        if (q.contains("🍉")) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(R.drawable.watermelon_chip);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(Ui.dp(10), Ui.dp(12), Ui.dp(10), 0);
            results.addView(iv, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            return;
        }

        if (q.equalsIgnoreCase("private")) {
            List<AppInfo> priv = new ArrayList<>();
            for (String key : LauncherApp.get().prefs.set(com.bb10.launcher.Prefs.HIDDEN)) {
                AppInfo ai = LauncherApp.get().apps.app(key);
                if (ai != null) priv.add(ai);
            }
            priv.sort((x, y) -> x.label().compareToIgnoreCase(y.label()));
            if (priv.isEmpty()) {
                TextView t = Ui.text(c, "No private apps. Hide an app from its options in edit mode.", 0, GREY, false);
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.03f * S);
                t.setPadding(Math.round(0.02f * S), Math.round(0.02f * S), Math.round(0.02f * S), 0);
                results.addView(t);
            } else {
                appSection(q, priv, true);
            }
            return;
        }

        List<AppInfo> apps = LauncherApp.get().apps.search(q);
        if (!apps.isEmpty() && shows(APPS)) appSection(q, apps, false);
        if (shows(ACTIONS)) actionSection(q);
        LinearLayout contacts = new LinearLayout(c);
        contacts.setOrientation(LinearLayout.VERTICAL);
        results.addView(contacts);
        if (shows(CONTACTS)) contactSection(c, contacts, q, gen);
        if (shows(MESSAGES)) messageSection(q);
        if (shows(EXTEND)) extendSection(q);
    }

    private View header(int icon, String title, int count, Runnable tap) {
        Context c = getContext();
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        for (java.util.Map.Entry<String, Integer> en : stripIcons.entrySet())
            if (en.getValue() == icon) anchors.put(en.getKey(), wrap);
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundColor(0xFF000000);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(Ui.glyph(c, icon, 0xFF8A8A8A));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round((ROW - 0.035f) / 2f * S);
        iv.setPadding(pad, 0, pad, 0);
        r.addView(iv, new LinearLayout.LayoutParams(Math.round(ROW * S), Math.round(0.035f * S)));
        TextView t = Ui.text(c, title, 0, HEADER_TEXT, true);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.031f * S);
        t.setSingleLine();
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = Math.round(0.006f * S);
        r.addView(t, tl);
        if (count >= 0) {
            TextView n = Ui.text(c, count + (count == 1 ? " Result" : " Results"), 0, GREY, false);
            n.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.024f * S);
            n.setPadding(0, 0, Math.round(0.028f * S), 0);
            r.addView(n);
        }
        if (tap != null) r.setOnClickListener(v -> tap.run());
        wrap.addView(r, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.round(HEADER * S)));
        View rule = new View(c);
        rule.setBackgroundColor(0xFF3D3F3D);
        wrap.addView(rule, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(2, Math.round(0.004f * S))));
        return wrap;
    }

    private View more(String section) {
        Context c = getContext();
        TextView t = Ui.text(c, "More", 0, HILITE, false);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.034f * S);
        t.setGravity(Gravity.CENTER);
        int p = Math.round(0.018f * S);
        t.setPadding(0, p, 0, p);
        t.setBackground(Ui.selectable(0));
        t.setOnClickListener(v -> {
            expanded.add(section);
            update();
        });
        return t;
    }

    private int limit(String section, int size) {
        return filter != null || expanded.contains(section) ? size : Math.min(size, PREVIEW);
    }

    private void setFirst(Runnable run, View row) {
        if (first != null) return;
        first = run;
        firstRow = row;
        View content = row.findViewWithTag("content");
        if (content == null) return;
        content.setBackgroundColor(SELECTED);
        TextView enter = (TextView) content.findViewWithTag("note");
        if (enter != null && enter.getText().length() == 0) {
            enter.setText("↵");
            enter.setTextColor(Ui.TEXT);
            enter.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.05f * S);
            enter.setVisibility(VISIBLE);
        }
    }

    private void appSection(String q, List<AppInfo> apps, boolean all) {
        results.addView(header(R.drawable.ic_s_apps, all ? "Private apps" : "Applications", apps.size(), null));
        int n = all ? apps.size() : limit(APPS, apps.size());
        for (int i = 0; i < n; i++) {
            AppInfo ai = apps.get(i);
            Runnable run = () -> {
                act.launchApp(ai.key, null);
                closeNow();
            };
            View r = item(LauncherApp.get().icons.get(ai), 0, 0, hilite(ai.label(), q), null, null, run);
            results.addView(r);
            setFirst(run, r);
        }
        if (n < apps.size()) results.addView(more(APPS));
    }

    private Drawable handlerIcon(Intent i) {
        try {
            ResolveInfo ri = getContext().getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null || "android".equals(ri.activityInfo.packageName)) return null;
            return LauncherApp.get().iconForPackage(ri.activityInfo.packageName);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Act {
        final int glyph, tile;
        final CharSequence label;
        final Intent iconFrom;
        final Runnable run;

        Act(int glyph, int tile, CharSequence label, Intent iconFrom, Runnable run) {
            this.glyph = glyph;
            this.tile = tile;
            this.label = label;
            this.iconFrom = iconFrom;
            this.run = run;
        }
    }

    private static CharSequence lab(String before, String part, String after) {
        SpannableString s = new SpannableString(before + part + after);
        if (!part.isEmpty())
            s.setSpan(new ForegroundColorSpan(HILITE), before.length(), before.length() + part.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private Act open(int glyph, int tile, CharSequence label, Intent i) {
        return new Act(glyph, tile, label, i, () -> start(i));
    }

    private static Intent view(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private Intent appOrWeb(String pkg, String action, String q, String url) {
        if (getContext().getPackageManager().getLaunchIntentForPackage(pkg) != null) {
            Intent i = new Intent(action).setPackage(pkg).putExtra(android.app.SearchManager.QUERY, q);
            if (getContext().getPackageManager().resolveActivity(i, 0) != null) return i;
        }
        return view(url);
    }

    private static final java.util.regex.Pattern
            PHONE = java.util.regex.Pattern.compile("[+0-9 ()\\-#*]{3,}"),
            EMAIL = java.util.regex.Pattern.compile("[^\\s@]+@[^\\s@]+\\.[a-zA-Z]{2,}"),
            URL = java.util.regex.Pattern.compile("(?i)(https?://)?(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}(/\\S*)?"),
            MATH = java.util.regex.Pattern.compile("[0-9.,+\\-*/x×÷()%^ ]+"),
            ALARM = java.util.regex.Pattern.compile("(?i)(?:set (?:an )?alarm(?: for| at)?|alarm(?: for| at)?|wake me(?: up)?(?: at)?)?\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?"),
            TIMER = java.util.regex.Pattern.compile("(?i)(?:set (?:a )?timer(?: for)?|timer)?\\s*(\\d+)\\s*(s|secs?|seconds?|m|mins?|minutes?|h|hrs?|hours?)(?:\\s*timer)?"),
            VERB = java.util.regex.Pattern.compile("(?i)(call|text|sms|email|mail|navigate to|directions to|drive to|map|play|listen to|note|remind me to|remind me|tweet|translate|youtube|wiki|wikipedia)\\s+(.+)");

    private void actionSection(String q) {
        List<Act> acts = new ArrayList<>();
        java.util.regex.Matcher m;

        int digits = q.replaceAll("\\D", "").length();
        boolean phoneLike = PHONE.matcher(q).matches() && q.indexOf('+', 1) < 0 && (digits >= 7 || !q.matches(".*\\d.*-.*\\d.*"));
        Double sum = !phoneLike && MATH.matcher(q).matches() && q.matches(".*\\d.*[+\\-*/x×÷%^].*\\d.*") ? calc(q) : null;
        if (sum != null) {
            String res = formatNumber(sum);
            acts.add(new Act(R.drawable.ic_done, 0xFF3A3A3A, lab(q + " = ", res, ""), null, () -> copy(res)));
        }
        String verb = null, rest = null;
        if ((m = VERB.matcher(q)).matches()) {
            verb = m.group(1).toLowerCase(Locale.ROOT);
            rest = m.group(2).trim();
        }
        String number = phoneLike ? q : ("call".equals(verb) && PHONE.matcher(rest).matches() ? rest : null);
        if (number != null) {
            Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
            acts.add(open(R.drawable.ic_phone, 0xFF5C8A1F, lab("Call ", number, ""), dial));
            Intent smsTo = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
            acts.add(open(R.drawable.ic_text, 0xFFE8B400, lab("Send a text message to ", number, ""), smsTo));
            acts.add(open(R.drawable.ph_add_contact, 0xFF3A3A3A, lab("Add ", number, " to Contacts"),
                    new Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.PHONE, number)));
        }
        if (EMAIL.matcher(q).matches()) {
            acts.add(open(R.drawable.ic_mail, 0xFF1E5A96, lab("Send an email to ", q, ""), new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + q))));
        } else if (!q.contains(" ") && URL.matcher(q).matches()) {
            acts.add(open(R.drawable.ic_web, 0xFF0A6EBD, lab("Open ", q, ""), view(q.matches("(?i)https?://.*") ? q : "https://" + q)));
        }
        if ((m = TIMER.matcher(q)).matches()) {
            int n = Integer.parseInt(m.group(1));
            char unit = Character.toLowerCase(m.group(2).charAt(0));
            int secs = unit == 'h' ? n * 3600 : unit == 'm' ? n * 60 : n;
            String what = m.group(1) + " " + (unit == 'h' ? (n == 1 ? "hour" : "hours") : unit == 'm' ? (n == 1 ? "minute" : "minutes") : (n == 1 ? "second" : "seconds"));
            acts.add(open(R.drawable.ic_alarm, 0xFF3A3A3A, lab("Set a timer for ", what, ""),
                    new Intent(android.provider.AlarmClock.ACTION_SET_TIMER).putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
                            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)));
        }
        if (number == null && (m = ALARM.matcher(q)).matches() && (m.group(2) != null || m.group(3) != null || !q.matches("\\d+"))) {
            int hr = Integer.parseInt(m.group(1)), min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            String ap = m.group(3);
            if (ap != null && ap.equalsIgnoreCase("pm") && hr < 12) hr += 12;
            if (ap != null && ap.equalsIgnoreCase("am") && hr == 12) hr = 0;
            if (hr < 24 && min < 60) {
                String when = String.format(Locale.ROOT, "%d:%02d", hr, min);
                acts.add(open(R.drawable.ic_alarm, 0xFF3A3A3A, lab("Set an alarm for ", when, ""),
                        new Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_HOUR, hr)
                                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, min)));
            }
        }
        if (verb != null && number == null) {
            switch (verb) {
                case "text": case "sms":
                    acts.add(open(R.drawable.ic_text, 0xFFE8B400, lab("Send a text message: ", rest, ""),
                            new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", rest)));
                    break;
                case "email": case "mail":
                    acts.add(open(R.drawable.ic_mail, 0xFF1E5A96, lab("Send an email: ", rest, ""),
                            new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:?body=" + Uri.encode(rest)))));
                    break;
                case "navigate to": case "directions to": case "drive to":
                    acts.add(open(R.drawable.ic_map, 0xFF3C8C3C, lab("Navigate to ", rest, ""), view("google.navigation:q=" + Uri.encode(rest))));
                    break;
                case "map":
                    acts.add(open(R.drawable.ic_map, 0xFF3C8C3C, lab("Find ", rest, " on the map"), view("geo:0,0?q=" + Uri.encode(rest))));
                    break;
                case "play": case "listen to":
                    acts.add(open(R.drawable.ic_m_play, 0xFFD2462D, lab("Play ", rest, ""), playIntent(rest)));
                    break;
                case "note":
                    acts.add(noteAct(rest));
                    break;
                case "remind me": case "remind me to":
                    acts.add(open(R.drawable.ic_event, 0xFF6BA82E, lab("Remind me to ", rest, ""),
                            new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, rest)));
                    break;
                case "tweet":
                    acts.add(open(R.drawable.ic_send, 0xFF1DA1F2, lab("Tweet: ", rest, ""), view("https://twitter.com/intent/tweet?text=" + Uri.encode(rest))));
                    break;
                case "translate":
                    acts.add(open(R.drawable.ic_web, 0xFF4285F4, lab("Translate ", rest, ""), view("https://translate.google.com/?sl=auto&text=" + Uri.encode(rest))));
                    break;
                case "youtube":
                    acts.add(open(R.drawable.ic_m_play, 0xFFE62117, lab("Search YouTube for ", rest, ""),
                            appOrWeb("com.google.android.youtube", Intent.ACTION_SEARCH, rest, "https://www.youtube.com/results?search_query=" + Uri.encode(rest))));
                    break;
                case "wiki": case "wikipedia":
                    acts.add(open(R.drawable.ic_web, 0xFF5A5A5A, lab("Search Wikipedia for ", rest, ""),
                            view("https://en.wikipedia.org/wiki/Special:Search?search=" + Uri.encode(rest))));
                    break;
            }
        }

        Runnable web = () -> {
            Intent i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q);
            if (!tryStart(i)) start(view("https://www.google.com/search?q=" + Uri.encode(q)));
        };
        acts.add(new Act(R.drawable.ic_search, 0xFF0A6EBD, lab("Search For: ", q, ""), null, web));
        acts.add(open(R.drawable.ic_text, 0xFFE8B400, "Send a text message", new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", q)));
        acts.add(open(R.drawable.ic_mail, 0xFF1E5A96, "Send an email", new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:?body=" + Uri.encode(q)))));
        acts.add(noteAct(q));
        acts.add(open(R.drawable.ic_event, 0xFF6BA82E, lab("Remind me: ", q, ""),
                new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, q)));
        acts.add(open(R.drawable.ic_map, 0xFF3C8C3C, lab("Find ", q, " on the map"), view("geo:0,0?q=" + Uri.encode(q))));
        acts.add(open(R.drawable.ic_map, 0xFF3C8C3C, lab("Navigate to ", q, ""), view("google.navigation:q=" + Uri.encode(q))));
        acts.add(open(R.drawable.ic_m_play, 0xFFD2462D, lab("Play ", q, ""), playIntent(q)));
        acts.add(open(R.drawable.ic_m_play, 0xFFE62117, lab("Search YouTube for ", q, ""),
                appOrWeb("com.google.android.youtube", Intent.ACTION_SEARCH, q, "https://www.youtube.com/results?search_query=" + Uri.encode(q))));
        acts.add(open(R.drawable.ic_web, 0xFF5A5A5A, lab("Search Wikipedia for ", q, ""), view("https://en.wikipedia.org/wiki/Special:Search?search=" + Uri.encode(q))));
        acts.add(open(R.drawable.ic_web, 0xFF4285F4, lab("Translate ", q, ""), view("https://translate.google.com/?sl=auto&text=" + Uri.encode(q))));
        acts.add(open(R.drawable.ic_open, 0xFF3A3A3A, lab("Search the store for ", q, ""), view("market://search?q=" + Uri.encode(q))));
        if (number == null)
            acts.add(open(R.drawable.ph_add_contact, 0xFF3A3A3A, lab("Add ", q, " to Contacts"),
                    new Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.NAME, q)));
        acts.add(open(R.drawable.ic_send, 0xFF1DA1F2, lab("Tweet: ", q, ""), view("https://twitter.com/intent/tweet?text=" + Uri.encode(q))));
        acts.add(new Act(R.drawable.ic_send, 0xFF3A3A3A, "Share", null, () ->
                start(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, q), "Share"))));
        acts.add(new Act(R.drawable.ic_edit, 0xFF3A3A3A, "Copy", null, () -> copy(q)));

        boolean open = filter != null || expanded.contains(ACTIONS);
        int n = open ? acts.size() : 1;
        results.addView(header(R.drawable.ic_s_action, "Instant Actions", n, open ? null : () -> {
            expanded.add(ACTIONS);
            update();
        }));
        for (int i = 0; i < n; i++) {
            Act a = acts.get(i);
            Drawable icon = a.iconFrom != null ? handlerIcon(a.iconFrom) : null;
            View r = item(icon, a.glyph, a.tile, a.label, null, null, a.run);
            results.addView(r);
            if (i == 0) setFirst(a.run, r);
        }
    }

    private Act noteAct(String text) {
        return new Act(R.drawable.ic_note, 0xFFE89A00, lab("Create a note: ", text, ""), null, () ->
                start(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Create a note with")));
    }

    private static Intent playIntent(String q) {
        return new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(android.app.SearchManager.QUERY, q)
                .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
    }

    private void copy(String s) {
        android.content.ClipboardManager cm = getContext().getSystemService(android.content.ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("search", s));
        Toast.makeText(getContext(), "Copied", Toast.LENGTH_SHORT).show();
    }

    private static String formatNumber(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = String.format(Locale.ROOT, "%.10f", d).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private String expr;
    private int pos;

    private Double calc(String s) {
        expr = s.replace(" ", "").replace(',', '.').replace('x', '*').replace('×', '*').replace('÷', '/');
        pos = 0;
        try {
            double v = sumExpr();
            if (pos != expr.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private double sumExpr() {
        double v = term();
        while (pos < expr.length()) {
            char c = expr.charAt(pos);
            if (c == '+') { pos++; v += term(); }
            else if (c == '-') { pos++; v -= term(); }
            else break;
        }
        return v;
    }

    private double term() {
        double v = power();
        while (pos < expr.length()) {
            char c = expr.charAt(pos);
            if (c == '*') { pos++; v *= power(); }
            else if (c == '/') { pos++; v /= power(); }
            else if (c == '%') { pos++; v = pos < expr.length() && "(0123456789.".indexOf(expr.charAt(pos)) >= 0 ? v % power() : v / 100; }
            else break;
        }
        return v;
    }

    private double power() {
        double v = unary();
        if (pos < expr.length() && expr.charAt(pos) == '^') {
            pos++;
            v = Math.pow(v, power());
        }
        return v;
    }

    private double unary() {
        if (pos < expr.length() && expr.charAt(pos) == '-') {
            pos++;
            return -unary();
        }
        if (pos < expr.length() && expr.charAt(pos) == '(') {
            pos++;
            double v = sumExpr();
            if (pos >= expr.length() || expr.charAt(pos) != ')') throw new IllegalStateException();
            pos++;
            return v;
        }
        int st = pos;
        while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) pos++;
        if (st == pos) throw new IllegalStateException();
        return Double.parseDouble(expr.substring(st, pos));
    }

    private void contactSection(Context c, LinearLayout box, String q, int gen) {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            box.addView(header(R.drawable.ph_contact, "Contacts", -1, null));
            box.addView(item(null, R.drawable.ic_info, 0xFF3A3A3A, "Allow contact search", null, null, act::requestContactsPermission));
            return;
        }
        LauncherApp.get().bg.execute(() -> {
            List<String[]> found = queryContacts(c, q);
            h.post(() -> {
                if (gen != generation || found.isEmpty()) return;
                box.addView(header(R.drawable.ph_contact, "Contacts", found.size(), null));
                int n = limit(CONTACTS, found.size());
                for (int i = 0; i < n; i++) {
                    String[] ct = found.get(i);
                    View r = item(null, R.drawable.ph_contact, 0xFF3A3A3A, hilite(ct[0], q), ct[1], null, () -> openContact(ct));
                    box.addView(r);
                    if (ct[2] != null) loadPhoto(ct[2], (ImageView) r.getTag());
                }
                if (n < found.size()) box.addView(more(CONTACTS));
                if (firstNumber == null) firstNumber = found.get(0)[1];
            });
        });
    }

    private void messageSection(String q) {
        List<HubItem> found;
        try {
            found = new ArrayList<>(LauncherApp.get().hub.items(null, q));
        } catch (Throwable t) {
            return;
        }
        if (found.isEmpty()) return;
        found.sort((x, y) -> Long.compare(y.time, x.time));
        results.addView(header(R.drawable.ic_mail, "Messages", found.size(), null));
        int n = limit(MESSAGES, found.size());
        for (int i = 0; i < n; i++) {
            HubItem it = found.get(i);
            Drawable icon = LauncherApp.get().iconForPackage(it.pkg);
            String when = DateUtils.isToday(it.time) ? DateFormat.getTimeFormat(getContext()).format(it.time)
                    : DateFormat.format("M/d/yy h:mm a", it.time).toString();
            results.addView(item(icon, 0, 0, hilite(it.title, q), hilite(it.text, q), when, () -> openMessage(it)));
        }
        if (n < found.size()) results.addView(more(MESSAGES));
    }

    private static final class Provider {
        final ComponentName cn;
        final boolean web;
        final String label;

        Provider(ComponentName cn, boolean web, String label) {
            this.cn = cn;
            this.web = web;
            this.label = label;
        }
    }

    private List<Provider> providers;

    private List<Provider> providers() {
        if (providers != null) return providers;
        List<Provider> out = new ArrayList<>(), rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        PackageManager pm = getContext().getPackageManager();
        String self = getContext().getPackageName();
        for (int pass = 0; pass < 2; pass++) {
            Intent i = new Intent(pass == 0 ? Intent.ACTION_WEB_SEARCH : Intent.ACTION_SEARCH);
            List<ResolveInfo> l;
            try {
                l = pm.queryIntentActivities(i, 0);
            } catch (Exception e) {
                continue;
            }
            for (ResolveInfo ri : l) {
                if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || pm.getLaunchIntentForPackage(pkg) == null || !seen.add(pkg)) continue;
                CharSequence lab = pm.getApplicationLabel(ri.activityInfo.applicationInfo);
                (pass == 0 ? out : rest).add(new Provider(new ComponentName(pkg, ri.activityInfo.name), pass == 0, lab == null ? pkg : lab.toString()));
            }
        }
        rest.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
        out.addAll(rest);
        return providers = out;
    }

    private void extendSection(String q) {
        List<Provider> ps = providers();
        results.addView(header(R.drawable.ic_s_globe, "Extend Search", -1, null));
        if (ps.isEmpty()) return;
        Context c = getContext();
        int cell = Math.round(0.22f * S), icon = Math.round(0.157f * S);
        LinearLayout row = null;
        for (int i = 0; i < ps.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(c);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.round(0.275f * S));
                if (i == 0) rl.topMargin = Math.round(0.039f * S);
                row.setPadding(Math.round((0.139f - SIDE - 0.11f) * S), 0, 0, 0);
                results.addView(row, rl);
            }
            Provider p = ps.get(i);
            LinearLayout v = new LinearLayout(c);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(c);
            Drawable pi = LauncherApp.get().iconForPackage(p.cn.getPackageName());
            iv.setImageDrawable(pi == null ? null : own(pi));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            v.addView(iv, new LinearLayout.LayoutParams(icon, icon));
            TextView t = Ui.text(c, p.label, 0, Ui.TEXT, false);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.029f * S);
            t.setSingleLine();
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setShadowLayer(Math.max(1f, 0.004f * S), 0, 0.0018f * S, 0xE6000000);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            tl.topMargin = Math.round(0.018f * S);
            v.addView(t, tl);
            v.setContentDescription(p.label);
            v.setOnClickListener(x -> {
                Intent it = new Intent(p.web ? Intent.ACTION_WEB_SEARCH : Intent.ACTION_SEARCH).setComponent(p.cn)
                        .putExtra(android.app.SearchManager.QUERY, q);
                start(it);
            });
            row.addView(v, new LinearLayout.LayoutParams(cell, LayoutParams.MATCH_PARENT));
        }
    }

    private View item(Drawable picture, int glyph, int tile, CharSequence title, CharSequence sub, String note, Runnable run) {
        Context c = getContext();
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r = new LinearLayout(c);
        r.setFocusable(true);
        r.setBackground(Ui.selectable(0));
        ImageView iv = new ImageView(c);
        int ts = Math.round(ROW * S);
        if (picture != null) {
            iv.setImageDrawable(own(picture));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            iv.setImageDrawable(Ui.glyph(c, glyph, Ui.TEXT));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = Math.round(0.026f * S);
            iv.setPadding(p, p, p, p);
            iv.setBackgroundColor(tile != 0 ? tile : 0xFF3A3A3A);
        }
        r.addView(iv, new LinearLayout.LayoutParams(ts, ts));
        wrap.setTag(iv);

        LinearLayout content = new LinearLayout(c);
        content.setTag("content");
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(Math.round(0.011f * S), 0, Math.round(0.028f * S), 0);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, "", 0, Ui.TEXT, false);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, (sub != null && sub.length() > 0 ? 0.04f : 0.046f) * S);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView d = Ui.text(c, "", 0, GREY, false);
            d.setText(sub);
            d.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.028f * S);
            d.setSingleLine();
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(0, Math.round(0.004f * S), 0, 0);
            col.addView(d);
        }
        content.addView(col, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView nt = Ui.text(c, note == null ? "" : note, 0, 0xFFB5B5B5, false);
        nt.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0.03f * S);
        nt.setPadding(Math.round(0.015f * S), 0, 0, 0);
        nt.setTag("note");
        nt.setVisibility(note == null ? GONE : VISIBLE);
        content.addView(nt);
        r.addView(content, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        r.setOnClickListener(v -> run.run());
        wrap.addView(r, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ts));
        View rule = new View(c);
        rule.setBackgroundColor(0xFF2F3237);
        wrap.addView(rule, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(2, Math.round(0.003f * S))));
        return wrap;
    }

    private Drawable own(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()).mutate() : d;
    }

    private static CharSequence hilite(String s, String q) {
        if (s == null) return "";
        SpannableString out = new SpannableString(s);
        if (q.isEmpty()) return out;
        String ls = s.toLowerCase(Locale.ROOT), lq = q.toLowerCase(Locale.ROOT);
        for (int i = ls.indexOf(lq); i >= 0; i = ls.indexOf(lq, i + lq.length()))
            out.setSpan(new ForegroundColorSpan(HILITE), i, i + lq.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    private static List<String[]> queryContacts(Context c, String q) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Uri uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q));
        String[] proj = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI, ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY};
        try (Cursor cur = c.getContentResolver().query(uri, proj, null, null, null)) {
            if (cur != null) {
                while (cur.moveToNext() && out.size() < 20) {
                    if (!seen.add(cur.getString(3))) continue;
                    out.add(new String[]{cur.getString(0), cur.getString(1), cur.getString(2), cur.getString(3), cur.getString(4)});
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void loadPhoto(String uri, ImageView iv) {
        LauncherApp.get().bg.execute(() -> {
            try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null) h.post(() -> {
                    iv.setPadding(0, 0, 0, 0);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageBitmap(b);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void openContact(String[] ct) {
        try {
            Uri u = ContactsContract.Contacts.getLookupUri(Long.parseLong(ct[3]), ct[4]);
            start(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            start(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(ct[1]))));
        }
    }

    private void openMessage(HubItem it) {
        if (com.bb10.launcher.hub.HubActions.openMail(getContext(), it)) {
            closeNow();
            return;
        }
        com.bb10.launcher.hub.HubActions.open(getContext(), LauncherApp.get().hub, it, (android.graphics.Rect) null);
        closeNow();
    }

    private void voice() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH);
        if (!tryStart(i) && !tryStart(new Intent(Intent.ACTION_VOICE_COMMAND)))
            Toast.makeText(getContext(), "No voice search available", Toast.LENGTH_SHORT).show();
    }

    private String firstNumber;

    private void callFirst() {
        String q = field.getText().toString().trim();
        String number = firstNumber;
        if (number == null && q.matches("[+0-9 ()\\-#*]{3,}")) number = q;
        if (number == null) {
            start(new Intent(Intent.ACTION_DIAL));
            return;
        }
        boolean canCall = getContext().checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        start(new Intent(canCall ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
    }

    private boolean tryStart(Intent i) {
        try {
            act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            closeNow();
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void start(Intent i) {
        if (!tryStart(i)) Toast.makeText(getContext(), "No app can do that", Toast.LENGTH_SHORT).show();
    }

    private void runFirst() {
        if (first != null) first.run();
    }

    private void closeNow() {
        release();
        act.closeSearch(this);
    }

    public void release() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        if (field.hasFocus()) field.clearFocus();
        if (imm != null && isAttachedToWindow()) imm.restartInput(field);
        field.setEnabled(false);
    }

    @Override
    public void close() {
        closeNow();
    }
}
