package com.bb10.launcher.hub;

import java.util.regex.Pattern;

public final class HtmlText {
    private HtmlText() {}

    private static final Pattern TAG = Pattern.compile(
            "(?i)<\\s*/?\\s*(a|abbr|b|big|blockquote|body|br|center|code|div|em|font|h[1-6]|head|hr|html|i|img|li|link|meta|ol|p|pre|s|small|span"
                    + "|strike|strong|style|sub|sup|table|tbody|td|th|thead|title|tr|tt|u|ul|wbr)\\b[^>]*>|<!--|<!doctype");
    private static final Pattern ENTITY = Pattern.compile("&(?:[a-zA-Z]{2,8}|#\\d{1,6}|#[xX][0-9a-fA-F]{1,6});");
    private static final Pattern HIDDEN = Pattern.compile("(?is)<(style|script|head|title)[^>]*>.*?</\\1\\s*>|<!--.*?-->");
    private static final Pattern HIDDEN_OPEN = Pattern.compile("(?is)<(style|script|head)[^>]*>.*$|<!--.*$");
    private static final Pattern CUT_TAG = Pattern.compile("<[^>]*$");

    private static final Pattern INVISIBLE_RUN = Pattern.compile(
            "(?:[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180E\\u200B\\u200C\\u200E\\u200F\\u2060-\\u2064\\u3164\\uFEFF\\uFFA0][\\s\\u00A0]*){2,}");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0]+");

    public static boolean hasHtml(String s) {
        if (s == null || s.isEmpty()) return false;
        return (s.indexOf('<') >= 0 && TAG.matcher(s).find()) || (s.indexOf('&') >= 0 && ENTITY.matcher(s).find());
    }

    public static String line(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        String t = hasHtml(s) ? strip(s) : s;
        return SPACES.matcher(padding(t)).replaceAll(" ").trim();
    }

    public static String plain(String s) {
        if (!hasHtml(s)) return s;
        return strip(s).trim();
    }

    public static String strip(String html) {
        String h = HIDDEN.matcher(html).replaceAll(" ");
        h = HIDDEN_OPEN.matcher(h).replaceAll(" ");
        h = CUT_TAG.matcher(h).replaceAll(" ");
        return padding(android.text.Html.fromHtml(h, android.text.Html.FROM_HTML_MODE_COMPACT).toString());
    }

    private static String padding(String t) {
        return INVISIBLE_RUN.matcher(t.replace("￼", "")).replaceAll(" ");
    }
}
