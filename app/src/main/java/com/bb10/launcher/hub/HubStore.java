package com.bb10.launcher.hub;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import com.bb10.launcher.LauncherApp;
import com.bb10.launcher.Prefs;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HubStore {
    public interface Listener {
        void onHubChanged();
    }

    private static final int MAX = 900;

    private final Context ctx;
    private final Prefs prefs;
    private final File file;
    private final List<HubItem> items = new ArrayList<>();
    private final Map<String, Notification> live = new HashMap<>();
    private final Map<String, Icon> smallIcons = new HashMap<>();
    private final Map<String, Icon> avatars = lru(200);
    private final Map<String, Notification> ongoingByPkg = new HashMap<>();
    private final Map<String, String> ongoingKeys = new HashMap<>();
    private final Map<String, Notification> ongoingByKey = new HashMap<>();
    private final List<NewItemListener> newItemListeners = new ArrayList<>();
    private final List<Runnable> ongoingListeners = new ArrayList<>();

    public interface NewItemListener {
        void onNewItem(HubItem it);
    }

    public void addNewItemListener(NewItemListener l) { if (!newItemListeners.contains(l)) newItemListeners.add(l); }

    public void removeNewItemListener(NewItemListener l) { newItemListeners.remove(l); }

    public void addOngoingListener(Runnable r) { if (!ongoingListeners.contains(r)) ongoingListeners.add(r); }

    public void removeOngoingListener(Runnable r) { ongoingListeners.remove(r); }

    private void fireOngoing() {
        h.removeCallbacks(ongoingRun);
        h.postDelayed(ongoingRun, 200);
    }

    private final Runnable ongoingRun = () -> {
        for (Runnable r : new ArrayList<>(ongoingListeners)) r.run();
    };

    public Notification ongoing(String pkg) { return ongoingByPkg.get(pkg); }

    public boolean isPriority(HubItem it) {
        return prefs.set(Prefs.VIP_APPS).contains(it.pkg) || prefs.set(Prefs.VIP_SENDERS).contains(senderOf(it));
    }

    public static String senderOf(HubItem it) {
        return it.title == null ? "" : it.title.trim();
    }

    public void setPriority(String setKey, String value, boolean on) {
        java.util.Set<String> s = prefs.set(setKey);
        if (on) s.add(value);
        else s.remove(value);
        prefs.putSet(setKey, s);
        changed();
    }

    public int unreadPriority() {
        int n = 0;
        for (HubItem it : items) if (!it.read && isPriority(it)) n++;
        return n;
    }

    public void markRead(List<HubItem> list) {
        for (HubItem it : list) it.read = true;
        changed();
        mailRead(list, true);
    }

    public void delete(List<HubItem> list) {
        mailDeleted(list);
        for (HubItem it : list) {
            items.remove(it);
            if (it.active) {
                live.remove(it.key);
                if (!it.key.contains(SEP)) HubListenerService.cancel(it.key);
            }
        }
        changed();
    }
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable saveRun = this::save;
    private final Runnable fireRun = this::fire;

    public HubStore(Context c, Prefs p) {
        ctx = c;
        prefs = p;
        file = new File(c.getFilesDir(), "hub.json");
        learnedComm.addAll(p.set(COMM_LEARNED));
        if (learnedComm.remove("com.android.shell")) p.putSet(COMM_LEARNED, new HashSet<>(learnedComm));
        load();
        LauncherApp.get().bg.execute(this::tidyPictures);
    }

    private static final long PICTURES_MAX = 20L * 1024 * 1024;
    private int savedSinceTidy;

    private void tidyPictures() {
        List<java.io.File> all = new ArrayList<>();
        for (String d : new String[]{"avatars", "photos"}) {
            java.io.File[] l = new java.io.File(ctx.getCacheDir(), d).listFiles();
            if (l != null) java.util.Collections.addAll(all, l);
        }
        long cut = System.currentTimeMillis() - 14L * 86_400_000L;
        all.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0;
        for (java.io.File f : all) {
            total += f.length();
            if (f.lastModified() < cut || total > PICTURES_MAX)
                f.delete();
        }
    }

    private void savedPicture() {
        if (++savedSinceTidy >= 40) {
            savedSinceTidy = 0;
            tidyPictures();
        }
    }

    private static void writeWebp(android.graphics.Bitmap b, java.io.File f, int quality) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            b.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, quality, out);
        } catch (Exception ignored) {
        }
    }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    private final Map<String, String> childGroup = new HashMap<>();
    private final Map<String, String> loneSummary = new HashMap<>();

    public void posted(StatusBarNotification sbn) {
        try {
            postedInner(sbn);
        } catch (Throwable t) {
            android.util.Log.w("BB10Hub", "notification from " + sbn.getPackageName() + " skipped", t);
        }
    }

    private void postedInner(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(ctx.getPackageName())) return;
        Notification n = sbn.getNotification();
        String group = sbn.isGroup() ? sbn.getGroupKey() : null;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            if (group != null && childGroup.containsValue(group)) return;
            if (group != null) loneSummary.put(group, sbn.getKey());
        } else if (group != null) {
            childGroup.put(sbn.getKey(), group);
            String stand = loneSummary.remove(group);
            if (stand != null) {
                live.remove(stand);
                if (items.removeIf(x -> stand.equals(baseKey(x.key)))) changed();
            }
        }
        if (!sbn.isClearable()) {
            ongoingByPkg.put(pkg, n);
            ongoingKeys.put(sbn.getKey(), pkg);
            ongoingByKey.put(sbn.getKey(), n);
            fireOngoing();
            if (!prefs.bool(Prefs.HUB_ONGOING, false) && !prefs.bool(Prefs.HUB_ALL, true)) return;
        }
        if (prefs.set(Prefs.HUB_EXCLUDED).contains(pkg)) return;
        if (HubKinds.isComm(n) && !HubKinds.knownComm(pkg) && !learnedComm.contains(pkg)
                && LauncherApp.get().apps.firstForPackage(pkg) != null && learnedComm.add(pkg))
            prefs.putSet(COMM_LEARNED, new HashSet<>(learnedComm));
        if (Notification.CATEGORY_MISSED_CALL.equals(n.category) && CallLogHub.readable(ctx)) return;
        if (isMedia(n)) {
            String mk = sbn.getKey();
            if (items.removeIf(x -> mk.equals(x.key))) changed();
            return;
        }
        boolean all = prefs.bool(Prefs.HUB_ALL, true);

        Bundle ex = n.extras;
        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE_BIG));
        if (title.isEmpty()) title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = str(ex.getCharSequence(Notification.EXTRA_TEXT));

        Icon avatar = null;
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            Bundle m = (Bundle) msgs[msgs.length - 1];
            String body = str(m.getCharSequence("text"));
            CharSequence sender = m.getCharSequence("sender");
            if (sender == null) {
                Object sp = m.getParcelable("sender_person");
                if (sp instanceof Person) sender = ((Person) sp).getName();
            }
            Object person = m.getParcelable("sender_person");
            if (person instanceof Person) avatar = ((Person) person).getIcon();
            String conv = str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            if (!body.isEmpty()) {
                if (!conv.isEmpty()) {
                    title = conv;
                    text = (sender != null ? sender + ": " : "") + body;
                } else {
                    if (sender != null) title = sender.toString();
                    text = body;
                }
            }
        }
        String sub = cleanSub(str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT)));
        if (Notification.CATEGORY_EMAIL.equals(n.category) || MAIL_APPS.contains(pkg)) {
            title = HtmlText.plain(title);
            text = HtmlText.plain(text);
            sub = HtmlText.plain(sub);
        }
        if (title.isEmpty() && text.isEmpty() && !all) return;
        if (title.isEmpty()) title = LauncherApp.get().apps.labelForPackage(pkg);

        if (ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) {
            Object ci = ex.getParcelable("android.conversationIcon");
            if (ci instanceof Icon) avatar = (Icon) ci;
        }
        if (avatar == null) avatar = n.getLargeIcon();
        if (avatar == null) {
            Object li = ex.getParcelable(Notification.EXTRA_LARGE_ICON);
            if (li instanceof Icon) avatar = (Icon) li;
            else if (li instanceof android.graphics.Bitmap) avatar = Icon.createWithBitmap((android.graphics.Bitmap) li);
        }

        String key = sbn.getKey();
        if (n.getSmallIcon() != null) smallIcons.put(pkg, n.getSmallIcon());

        if (MULTI_CHAT.matcher(title + " \n " + text).find()) {
            CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            items.removeIf(x -> key.equals(x.key));
            if (lines == null || lines.length == 0) {
                changed();
                return;
            }
            live.put(key, n);
            for (CharSequence l : lines) {
                String s = str(l);
                if (s.isEmpty()) continue;
                int c = s.indexOf(": ");
                String chat = c > 0 ? s.substring(0, c) : LauncherApp.get().apps.labelForPackage(pkg);
                String body = c > 0 ? s.substring(c + 2) : s;
                String lineKey = key + SEP + chat;
                live.put(lineKey, n);
                upsert(sbn, lineKey, chat, body, "", null);
            }
            changed();
            return;
        }

        if (msgs != null && msgs.length > 0 && prefs.bool(Prefs.HUB_SPLIT, true) && splitMessages(sbn, n, msgs, sub)) {
            items.removeIf(x -> key.equals(x.key));
            live.put(key, n);
            changed();
            return;
        }

        live.put(key, n);
        upsert(sbn, key, title, text, sub, avatar);
        changed();
    }

    private boolean splitMessages(StatusBarNotification sbn, Notification n, Parcelable[] msgs, String sub) {
        Bundle ex = n.extras;
        String key = sbn.getKey();
        String conv = str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        boolean groupChat = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || !conv.isEmpty();
        String me = "";
        Object user = ex.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
        if (user instanceof Person && ((Person) user).getName() != null) me = ((Person) user).getName().toString();
        if (me.isEmpty()) me = str(ex.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME));
        Icon chatIcon = null;
        Object ci = ex.getParcelable("android.conversationIcon");
        if (ci instanceof Icon) chatIcon = (Icon) ci;
        if (chatIcon == null) chatIcon = n.getLargeIcon();
        int added = 0;
        for (Parcelable p : msgs) {
            if (!(p instanceof Bundle)) continue;
            Bundle m = (Bundle) p;
            CharSequence sender = m.getCharSequence("sender");
            Object sp = m.getParcelable("sender_person");
            Person person = sp instanceof Person ? (Person) sp : null;
            if (sender == null && person != null) sender = person.getName();
            if (sender == null || sender.toString().isEmpty() || sender.toString().equals(me)) continue;
            String body = str(m.getCharSequence("text"));
            if (body.isEmpty()) body = m.containsKey("uri") ? "Photo" : "";
            if (body.isEmpty()) continue;
            long time = m.getLong("time", sbn.getPostTime());
            String who = sender.toString();
            String title = groupChat && !conv.isEmpty() ? conv : who;
            String text = groupChat && !conv.isEmpty() ? who + ": " + body : body;
            String msgKey = key + SEP + "m" + time + ":" + who.hashCode();
            for (HubItem x : items) {
                if (!x.key.equals(msgKey) && key.equals(baseKey(x.key)) && x.title.equals(title) && x.text.equals(text)
                        && Math.abs(x.time - time) < 5 * 60_000L) {
                    msgKey = x.key;
                    time = x.time;
                    break;
                }
            }
            Icon avatar = groupChat ? chatIcon
                    : person != null && person.getIcon() != null ? person.getIcon() : chatIcon;
            live.put(msgKey, n);
            HubItem it = upsert(sbn, msgKey, title, text, "", avatar, time);
            Object u = m.getParcelable("uri");
            String type = m.getString("type");
            if (u instanceof android.net.Uri && (type == null || type.startsWith("image/"))) keepPhoto(it, (android.net.Uri) u);
            added++;
        }
        return added > 0;
    }

    static final String SEP = "\u0001";
    private static final java.util.regex.Pattern MULTI_CHAT = java.util.regex.Pattern.compile(
            "(?i)\\b\\d+\\s+(new\\s+|unread\\s+)?(messages?|notifications?)\\b.{0,20}\\bfrom\\s+\\d+\\s+(chats?|conversations?|contacts?|groups?)\\b");

    private static final java.util.regex.Pattern COUNT_LINE = java.util.regex.Pattern.compile(
            "(?i)[\\s・•·,|-]*\\b\\d+\\s+(new\\s+|unread\\s+)?(messages?|notifications?)\\b(.{0,20}\\bfrom\\s+\\d+\\s+(chats?|conversations?|contacts?|groups?)\\b)?");

    static String cleanSub(String sub) {
        if (sub == null || sub.isEmpty()) return "";
        String s = COUNT_LINE.matcher(sub).replaceAll("").trim();
        return s.replaceAll("^[・•·,|\\-\\s]+|[・•·,|\\-\\s]+$", "");
    }

    private void upsert(StatusBarNotification sbn, String key, String title, String text, String sub, Icon avatar) {
        upsert(sbn, key, title, text, sub, avatar, sbn.getPostTime());
    }

    private HubItem upsert(StatusBarNotification sbn, String key, String title, String text, String sub, Icon avatar, long postTime) {
        boolean fresh = false;
        HubItem it = null;
        for (HubItem x : items) {
            if (key.equals(x.key) && (x.active || (x.title.equals(title) && x.text.equals(text)))) {
                it = x;
                break;
            }
        }
        boolean wasThere = it != null;
        if (it == null) {
            String id = key + "#" + postTime;
            for (HubItem x : items) {
                if (id.equals(x.id)) {
                    it = x;
                    break;
                }
            }
            if (it != null) {
                wasThere = true;
                if (!title.equals(it.title) || !text.equals(it.text)) {
                    it.read = false;
                    fresh = true;
                }
            }
        }
        if (it == null) {
            it = new HubItem();
            it.id = key + "#" + postTime;
            it.key = key;
            it.pkg = sbn.getPackageName();
            it.time = postTime;
            it.read = false;
            items.add(it);
            fresh = true;
        } else if (!title.equals(it.title) || !text.equals(it.text)) {
            it.read = false;
            it.time = Math.max(it.time, postTime);
            fresh = true;
        }
        it.title = title;
        it.text = text;
        it.sub = sub;
        it.active = true;
        it.persistent = !sbn.isClearable();
        if (it.persistent) it.read = true;
        if (avatar != null) avatars.put(it.id, avatar);
        keepAvatar(it, sbn, avatar);
        if (sbn.getNotification().contentIntent != null) {
            if (targets.size() > 1000) targets.clear();
            targets.put(it.id, sbn.getNotification().contentIntent);
        }
        if (fresh && !syncing && !it.read && alerts(sbn, key, wasThere) && firstTimeShown(sbn, it)) {
            HubItem fi = it;
            h.post(() -> {
                for (NewItemListener l : new ArrayList<>(newItemListeners)) l.onNewItem(fi);
            });
        }
        return it;
    }

    private final Map<String, android.graphics.Bitmap> photos = lru(40);
    private final Set<String> photoMissing = java.util.Collections.newSetFromMap(lru(2000));

    static <K, V> Map<K, V> lru(int max) {
        return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<K, V>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > max; }
        });
    }

    private java.io.File photoFile(String id) {
        java.io.File dir = new java.io.File(ctx.getCacheDir(), "photos");
        dir.mkdirs();
        return new java.io.File(dir, Integer.toHexString(id.hashCode()) + ".jpg");
    }

    public android.graphics.Bitmap photo(HubItem it) {
        android.graphics.Bitmap b = photos.get(it.id);
        if (b != null || photoMissing.contains(it.id)) return b;
        java.io.File f = photoFile(it.id);
        b = f.exists() ? android.graphics.BitmapFactory.decodeFile(f.getPath()) : null;
        if (b != null) photos.put(it.id, b);
        else photoMissing.add(it.id);
        return b;
    }

    private void keepPhoto(HubItem it, android.net.Uri uri) {
        String id = it.id;
        java.io.File f = photoFile(id);
        if (f.exists()) return;
        LauncherApp.get().bg.execute(() -> {
            android.graphics.Bitmap b = null;
            try {
                android.content.ContentResolver cr = ctx.getContentResolver();
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (java.io.InputStream in = cr.openInputStream(uri)) {
                    android.graphics.BitmapFactory.decodeStream(in, null, o);
                }
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 240) s *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = s;
                try (java.io.InputStream in = cr.openInputStream(uri)) {
                    b = android.graphics.BitmapFactory.decodeStream(in, null, o);
                }
            } catch (Throwable t) {
                android.util.Log.i("BB10Hub", "message photo not readable: " + t);
            }
            if (b == null) b = photoAsRoot(uri);
            if (b == null) return;
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(b.getWidth(), b.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            c.drawColor(0xFFFFFFFF);
            c.drawBitmap(b, 0, 0, null);
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, os);
            } catch (Exception ignored) {
            }
            savedPicture();
            h.post(() -> {
                photos.put(id, out);
                photoMissing.remove(id);
                changed();
            });
        });
    }

    private android.graphics.Bitmap photoAsRoot(android.net.Uri uri) {
        String u = uri.toString();
        if (!"content".equals(uri.getScheme()) || u.indexOf('\'') >= 0 || !com.bb10.launcher.RootShell.knownAvailable()) return null;
        java.io.File tmp = new java.io.File(ctx.getCacheDir(), "photo-" + Integer.toHexString(u.hashCode()) + ".tmp");
        try {
            com.bb10.launcher.RootShell.Result r = com.bb10.launcher.RootShell.exec(8000,
                    "content read --uri '" + u + "' > '" + tmp.getPath() + "' && chmod 644 '" + tmp.getPath() + "'");
            if (!r.ok || !tmp.exists() || tmp.length() == 0) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(tmp.getPath(), o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 240) s *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = s;
            return android.graphics.BitmapFactory.decodeFile(tmp.getPath(), o);
        } catch (Throwable t) {
            return null;
        } finally {
            com.bb10.launcher.RootShell.exec(3000, "rm -f '" + tmp.getPath() + "'");
        }
    }

    private final java.util.Set<String> bannered = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private boolean firstTimeShown(StatusBarNotification sbn, HubItem it) {
        Notification n = sbn.getNotification();
        String tpl = n.extras.getString(Notification.EXTRA_TEMPLATE);
        boolean message = Notification.CATEGORY_MESSAGE.equals(n.category)
                || (tpl != null && tpl.contains("MessagingStyle")) || it.key.contains(SEP);
        String id = message ? it.key + "\n" + it.title + "\n" + it.text : baseKey(it.key);
        synchronized (bannered) {
            if (bannered.contains(id)) return false;
            if (bannered.size() > 500) {
                java.util.Iterator<String> i = bannered.iterator();
                for (int k = 0; k < 100 && i.hasNext(); k++) {
                    i.next();
                    i.remove();
                }
            }
            bannered.add(id);
        }
        android.app.KeyguardManager km = ctx.getSystemService(android.app.KeyguardManager.class);
        android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
        return !(km != null && km.isKeyguardLocked()) && (pm == null || pm.isInteractive());
    }

    private boolean alerts(StatusBarNotification sbn, String key, boolean update) {
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return false;
        if (Notification.CATEGORY_CALL.equals(n.category) && n.fullScreenIntent != null) return false;
        Bundle ex = n.extras;
        if (ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return false;
        String cat = n.category;
        return !(Notification.CATEGORY_PROGRESS.equals(cat) || Notification.CATEGORY_SERVICE.equals(cat)
                || Notification.CATEGORY_TRANSPORT.equals(cat) || Notification.CATEGORY_NAVIGATION.equals(cat)
                || Notification.CATEGORY_STOPWATCH.equals(cat) || Notification.CATEGORY_STATUS.equals(cat));
    }

    static String baseKey(String key) {
        int i = key == null ? -1 : key.indexOf(SEP);
        return i < 0 ? key : key.substring(0, i);
    }
    static boolean isMedia(Notification n) {
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true;
        if (Notification.CATEGORY_TRANSPORT.equals(n.category)) return true;
        String tpl = n.extras.getString(Notification.EXTRA_TEMPLATE);
        return tpl != null && tpl.contains("MediaStyle");
    }

    public void removed(String key) {
        bannered.remove(key);
        childGroup.remove(key);
        loneSummary.values().remove(key);
        live.remove(key);
        live.keySet().removeIf(k -> k.startsWith(key + SEP));
        String op = ongoingKeys.remove(key);
        ongoingByKey.remove(key);
        if (op != null) {
            Notification still = null;
            for (Map.Entry<String, String> e : ongoingKeys.entrySet()) if (e.getValue().equals(op)) still = ongoingByKey.get(e.getKey());
            if (still != null) ongoingByPkg.put(op, still);
            else ongoingByPkg.remove(op);
            fireOngoing();
        }
        boolean keep = prefs.bool(Prefs.HUB_HISTORY, true);
        boolean any = false;
        for (Iterator<HubItem> i = items.iterator(); i.hasNext(); ) {
            HubItem it = i.next();
            if (it.active && key.equals(baseKey(it.key))) {
                any = true;
                if (!keep || it.persistent) {
                    i.remove();
                } else {
                    it.active = false;
                    it.read = true;
                }
            }
        }
        if (any) changed();
    }

    private boolean syncing;

    public void sync(StatusBarNotification[] arr) {
        syncing = true;
        try {
            syncInner(arr);
        } finally {
            syncing = false;
        }
    }

    private void syncInner(StatusBarNotification[] arr) {
        Set<String> keys = new HashSet<>();
        if (arr != null) {
            arr = arr.clone();
            java.util.Arrays.sort(arr, (a, b) -> Boolean.compare(
                    (a.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                    (b.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0));
            for (StatusBarNotification s : arr) {
                keys.add(s.getKey());
                posted(s);
            }
        }
        for (HubItem it : new ArrayList<>(items)) {
            if (it.active && !keys.contains(baseKey(it.key))) removed(baseKey(it.key));
        }
        changed();
    }

    private static final String COMM_LEARNED = "hub_comm_learned";
    private final Set<String> learnedComm = new HashSet<>();

    public String[] learnedComm() {
        return learnedComm.toArray(new String[0]);
    }

    public boolean isComm(String pkg) {
        return HubKinds.knownComm(pkg) || learnedComm.contains(pkg) || pkg.equals(defaultSms());
    }

    private String sms;
    private long smsAt;

    private String defaultSms() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - smsAt > 60_000L) {
            smsAt = now;
            try {
                sms = android.provider.Telephony.Sms.getDefaultSmsPackage(ctx);
            } catch (Exception e) {
                sms = null;
            }
        }
        return sms == null ? "" : sms;
    }

    public String group(String pkg) {
        return HubKinds.group(pkg, isComm(pkg));
    }

    public boolean in(HubItem it, String f) {
        if (f == null) return true;
        if (f.equals(it.pkg)) return true;
        return (f.equals(HubKinds.NOTIF) || f.equals(HubKinds.CALLS)) && f.equals(group(it.pkg));
    }

    public List<String> groups() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (HubItem it : items) s.add(group(it.pkg));
        return new ArrayList<>(s);
    }

    public List<String> unreadGroups() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (HubItem it : items) if (!it.read) s.add(group(it.pkg));
        return new ArrayList<>(s);
    }

    public List<HubItem> items(String pkg, String query) {
        List<HubItem> out = new ArrayList<>();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        for (HubItem it : items) {
            if (!in(it, pkg)) continue;
            if (!q.isEmpty() && !it.title.toLowerCase(Locale.ROOT).contains(q)
                    && !it.text.toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(it);
        }
        return out;
    }

    public int unread(String pkg) {
        int n = 0;
        for (HubItem it : items) if (!it.read && in(it, pkg)) n++;
        return n;
    }

    public List<String> packages() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (HubItem it : items) s.add(it.pkg);
        return new ArrayList<>(s);
    }

    public List<String> unreadPackages() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (HubItem it : items) if (!it.read) s.add(it.pkg);
        return new ArrayList<>(s);
    }

    public Notification live(HubItem it) {
        return it.active ? live.get(it.key) : null;
    }

    public Notification liveByKey(String key) {
        return key == null ? null : live.get(key);
    }

    private final Map<String, android.app.PendingIntent> targets = new HashMap<>();

    public android.app.PendingIntent target(HubItem it) {
        Notification n = live(it);
        if (n != null && n.contentIntent != null) return n.contentIntent;
        return targets.get(it.id);
    }

    public Icon smallIcon(String pkg) { return smallIcons.get(pkg); }

    public Icon avatar(HubItem it) {
        Icon i = savedAvatars.get(it.id);
        if (i != null) return i;
        if (!avatarMissing.contains(it.id)) {
            java.io.File f = avatarFile(it.id);
            android.graphics.Bitmap b = f.exists() ? android.graphics.BitmapFactory.decodeFile(f.getPath()) : null;
            if (b != null) {
                i = Icon.createWithBitmap(b);
                savedAvatars.put(it.id, i);
                return i;
            }
            avatarMissing.add(it.id);
        }
        return avatars.get(it.id);
    }

    private final Set<String> avatarMissing = java.util.Collections.newSetFromMap(lru(2000));
    private final Map<String, Icon> savedAvatars = lru(60);

    public boolean avatarSaved(HubItem it) {
        avatar(it);
        return savedAvatars.containsKey(it.id);
    }

    private java.io.File avatarFile(String id) {
        java.io.File dir = new java.io.File(ctx.getCacheDir(), "avatars");
        dir.mkdirs();
        return new java.io.File(dir, Integer.toHexString(id.hashCode()) + ".png");
    }

    private void keepAvatar(HubItem it, StatusBarNotification sbn, Icon given) {
        String id = it.id;
        if (avatarFile(id).exists()) return;
        avatarMissing.remove(id);
        String shortcut = sbn.getNotification().getShortcutId();
        android.os.UserHandle user = sbn.getUser();
        String pkg = sbn.getPackageName();
        if (given == null && shortcut == null) return;
        LauncherApp.get().bg.execute(() -> {
            android.graphics.drawable.Drawable d = null;
            try {
                if (given != null) d = given.loadDrawable(ctx);
            } catch (Exception ignored) {
            }
            if (d == null && shortcut != null) d = shortcutIcon(pkg, shortcut, user);
            if (d == null) return;
            int s = 192;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, s, s);
            d.draw(c);
            writeWebp(b, avatarFile(id), 90);
            savedPicture();
            h.post(() -> {
                savedAvatars.put(id, Icon.createWithBitmap(b));
                avatarMissing.remove(id);
                changed();
            });
        });
    }

    private android.graphics.drawable.Drawable shortcutIcon(String pkg, String id, android.os.UserHandle user) {
        try {
            android.content.pm.LauncherApps la = ctx.getSystemService(android.content.pm.LauncherApps.class);
            if (la == null || !la.hasShortcutHostPermission()) return null;
            android.content.pm.LauncherApps.ShortcutQuery q = new android.content.pm.LauncherApps.ShortcutQuery()
                    .setPackage(pkg)
                    .setShortcutIds(java.util.Collections.singletonList(id))
                    .setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                            | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED);
            List<android.content.pm.ShortcutInfo> l = la.getShortcuts(q, user);
            if (l == null || l.isEmpty()) return null;
            return la.getShortcutIconDrawable(l.get(0), ctx.getResources().getDisplayMetrics().densityDpi);
        } catch (Exception e) {
            return null;
        }
    }

    public HubItem latestUnreadSince(String pkg, long since) {
        for (HubItem it : items) {
            if (!it.read && pkg.equals(it.pkg) && it.time > since) return it;
        }
        return null;
    }

    public static final String MAIL = "mail:";

    public static boolean isMail(HubItem it) { return it.key != null && it.key.startsWith(MAIL); }

    public static boolean isMailPkg(String pkg) { return pkg != null && pkg.startsWith(MAIL); }

    public interface MailHook {
        void read(List<HubItem> items, boolean read);

        void deleted(List<HubItem> items);
    }

    public static volatile MailHook mailHook;

    private static List<HubItem> mailOnly(java.util.Collection<HubItem> l) {
        List<HubItem> out = new ArrayList<>();
        for (HubItem it : l) if (isMail(it)) out.add(it);
        return out;
    }

    private void mailRead(java.util.Collection<HubItem> l, boolean read) {
        MailHook m = mailHook;
        List<HubItem> mail = mailOnly(l);
        if (m != null && !mail.isEmpty()) m.read(mail, read);
        CallLogHub.read(ctx, l, read);
    }

    private void mailDeleted(java.util.Collection<HubItem> l) {
        MailHook m = mailHook;
        List<HubItem> mail = mailOnly(l);
        if (m != null && !mail.isEmpty()) m.deleted(mail);
        CallLogHub.deleted(ctx, l);
    }

    public static boolean isCall(HubItem it) { return it.key != null && it.key.startsWith(HubKinds.CALLS); }

    public void putMail(String pkg, List<HubItem> now, boolean announce) {
        Map<String, HubItem> had = new HashMap<>();
        for (HubItem it : items) if (pkg.equals(it.pkg)) had.put(it.id, it);
        Set<String> keep = new HashSet<>();
        List<HubItem> fresh = new ArrayList<>();
        for (HubItem m : now) {
            keep.add(m.id);
            HubItem it = had.get(m.id);
            if (it == null) {
                items.add(m);
                if (announce && !m.read) fresh.add(m);
            } else {
                it.title = m.title;
                it.text = m.text;
                it.sub = m.sub;
                it.time = m.time;
                it.read = m.read;
            }
        }
        items.removeIf(it -> pkg.equals(it.pkg) && !keep.contains(it.id));
        changed();
        if (!fresh.isEmpty()) h.post(() -> {
            for (HubItem fi : fresh) for (NewItemListener l : new ArrayList<>(newItemListeners)) l.onNewItem(fi);
        });
    }

    public void removeMail(String pkg) {
        if (items.removeIf(it -> pkg.equals(it.pkg))) changed();
    }

    public HubItem byId(String id) {
        for (HubItem it : items) if (id.equals(it.id)) return it;
        return null;
    }

    public void markReadByKey(String key) {
        if (key == null) return;
        java.util.List<HubItem> hit = new java.util.ArrayList<>();
        for (HubItem it : items) if (key.equals(it.key) && !it.read) hit.add(it);
        for (HubItem it : hit) {
            it.read = true;
            mailRead(java.util.Collections.singletonList(it), true);
        }
        if (!hit.isEmpty()) changed();
    }

    public void setRead(HubItem it, boolean read) {
        it.read = read;
        changed();
        if (read) clearRead(java.util.Collections.singletonList(it));
        mailRead(java.util.Collections.singletonList(it), read);
    }

    private void clearRead(List<HubItem> touched) {
        Set<String> keys = new HashSet<>();
        for (HubItem t : touched) if (t.active && !t.persistent && t.read) keys.add(baseKey(t.key));
        if (keys.isEmpty()) return;
        for (HubItem o : items) if (o.active && !o.read) keys.remove(baseKey(o.key));
        for (String k : keys) HubListenerService.cancel(k);
    }

    public void markPriorRead(HubItem ref) {
        List<HubItem> touched = new ArrayList<>();
        for (HubItem it : items) if (it.time <= ref.time && !it.read) {
            it.read = true;
            touched.add(it);
        }
        changed();
        clearRead(touched);
        mailRead(touched, true);
    }

    public void markAllRead(String pkg) {
        markAllRead(pkg, false);
    }

    public void markAllRead(String pkg, boolean priorityOnly) {
        Set<String> clear = new HashSet<>();
        List<HubItem> touched = new ArrayList<>();
        for (HubItem it : items) {
            if (!in(it, pkg)) continue;
            if (priorityOnly && !isPriority(it)) continue;
            if (!it.read) touched.add(it);
            it.read = true;
            if (it.active && !it.persistent) clear.add(baseKey(it.key));
        }
        changed();
        mailRead(touched, true);
        for (String key : clear) HubListenerService.cancel(key);
        if (pkg == null && !priorityOnly) HubListenerService.cancelAll();
    }

    public void clearAll(String pkg, boolean priorityOnly) {
        Set<String> clear = new HashSet<>();
        List<HubItem> gone = new ArrayList<>();
        items.removeIf(it -> {
            if (!in(it, pkg)) return false;
            if (priorityOnly && !isPriority(it)) return false;
            if (it.persistent) return false;
            if (isCall(it) && !HubKinds.CALLS.equals(pkg)) return false;
            gone.add(it);
            if (it.active) {
                live.remove(it.key);
                clear.add(baseKey(it.key));
            }
            return true;
        });
        changed();
        mailDeleted(gone);
        for (String key : clear) HubListenerService.cancel(key);
        if (pkg == null && !priorityOnly) HubListenerService.cancelAll();
    }

    public void delete(HubItem it) {
        mailDeleted(java.util.Collections.singletonList(it));
        items.remove(it);
        if (it.active) {
            live.remove(it.key);
            if (!it.key.contains(SEP)) HubListenerService.cancel(it.key);
        }
        changed();
    }

    public void clearHistory() {
        items.removeIf(it -> !it.active && !isMail(it) && !isCall(it));
        changed();
    }

    private void changed() {
        items.sort((a, b) -> Long.compare(b.time, a.time));
        while (items.size() > MAX) items.remove(items.size() - 1);
        h.removeCallbacks(saveRun);
        h.postDelayed(saveRun, 1500);
        h.removeCallbacks(fireRun);
        h.post(fireRun);
    }

    private void fire() {
        for (Listener l : new ArrayList<>(listeners)) l.onHubChanged();
    }

    private void save() {
        final String json;
        try {
            JSONArray a = new JSONArray();
            for (HubItem it : items) a.put(it.toJson());
            json = a.toString();
        } catch (Exception e) {
            return;
        }
        LauncherApp.get().bg.execute(() -> {
            try {
                File tmp = new File(file.getPath() + ".tmp");
                try (FileOutputStream os = new FileOutputStream(tmp)) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                tmp.renameTo(file);
            } catch (Exception ignored) {
            }
        });
    }

    private void load() {
        if (!file.exists()) return;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                HubItem it = HubItem.fromJson(a.getJSONObject(i));
                if (MULTI_CHAT.matcher(it.title + " \n " + it.text).find()) continue;
                if (it.key != null && it.key.contains(SEP)) it.sub = "";
                else if (!isMail(it)) it.sub = cleanSub(it.sub);
                items.add(it);
            }
        } catch (Exception ignored) {
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private static final Set<String> MAIL_APPS = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.fsck.k9", "net.thunderbird.android",
            "com.android.email", "eu.faircode.email", "com.readdle.spark", "ch.protonmail.android",
            "com.yahoo.mobile.client.android.mail", "me.bluemail.mail", "org.kman.AquaMail",
            "com.samsung.android.email.provider", "com.boxer.email", "de.tutao.tutanota", "com.zoho.mail",
            "ru.mail.mailapp", "com.blackberry.hub", "com.blackberry.email"));
}
