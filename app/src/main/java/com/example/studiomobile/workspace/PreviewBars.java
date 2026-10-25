package com.example.studiomobile.workspace;

import android.content.res.Resources;
import android.graphics.Color;
import android.util.TypedValue;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the preview's phone shows in its status bar, navigation bar and title bar.
 * Everything comes from the user's project: the theme (read through the real compiled resources) and, on top of it,
 * the calls found in the Activity source (setStatusBarColor, FLAG_FULLSCREEN, hide(), ...). Java is never run, it is only read.
 * A bar that the project hides is not shown at all.
 */
public final class PreviewBars {
    public boolean showStatus = true, showNav = true, actionBar, edgeToEdge;
    /** The source hides system bars in a way that normally permits transient reveal by an edge swipe. */
    public boolean transientBars;
    /** Filled from the project's theme; when the theme says nothing, from the Android default theme (see {@link #systemDefaults}). */
    public int statusColor, navColor, actionBarColor;
    public boolean lightStatus, lightNav;
    public int behindColor = Color.WHITE;
    /** The code asks for the "up" arrow in the action bar (setDisplayHomeAsUpEnabled(true)). */
    public boolean homeAsUp;
    public String statusInfo = "", navInfo = "", actionBarInfo = "";
    public String title = "";

    // ------------------------------------------------------------------ values found in Java/Kotlin source

    private static final class Found<T> {
        final T value; final String where; final int position;
        Found(T value, String where, int position) { this.value = value; this.where = where; this.position = position; }
    }

    /** Result of reading one Activity source file. Null fields mean "not mentioned in code". */
    static final class CodeScan {
        Found<Integer> statusColor, navColor;
        boolean statusColorDynamic, navColorDynamic; String statusDynamicWhere = "", navDynamicWhere = "";
        Found<Boolean> lightStatus, lightNav, edgeToEdge, actionBarVisible, noTitleFeature, homeAsUp;
        boolean transientBars;
        final List<Found<Boolean>> statusVisibility = new ArrayList<>(), navVisibility = new ArrayList<>();
    }

    private static final Pattern PARSE_COLOR = Pattern.compile("Color\\s*\\.\\s*parseColor\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern HEX = Pattern.compile("\\b0[xX]([0-9a-fA-F]{6,8})\\b");
    private static final Pattern RGB = Pattern.compile("Color\\s*\\.\\s*rgb\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern ARGB = Pattern.compile("Color\\s*\\.\\s*argb\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern NAMED = Pattern.compile("\\bColor\\s*\\.\\s*(RED|BLUE|GREEN|BLACK|WHITE|TRANSPARENT|GRAY|DKGRAY|LTGRAY|YELLOW|CYAN|MAGENTA)\\b");
    private static final Pattern APP_COLOR = Pattern.compile("(?<![\\w.])R\\s*\\.\\s*color\\s*\\.\\s*(\\w+)");
    private static final Pattern ANDROID_COLOR = Pattern.compile("android\\s*\\.\\s*R\\s*\\.\\s*color\\s*\\.\\s*(\\w+)");
    private static final Pattern SET_STATUS_COLOR = Pattern.compile("setStatusBarColor\\s*\\(");
    private static final Pattern SET_NAV_COLOR = Pattern.compile("setNavigationBarColor\\s*\\(");
    private static final Pattern ASSIGN_STATUS_COLOR = Pattern.compile("\\bstatusBarColor\\s*=(?!=)\\s*([^;\\n]+)");
    private static final Pattern ASSIGN_NAV_COLOR = Pattern.compile("\\bnavigationBarColor\\s*=(?!=)\\s*([^;\\n]+)");
    private static final Pattern LIGHT_STATUS = Pattern.compile("(?:setAppearanceLightStatusBars\\s*\\(|\\bisAppearanceLightStatusBars\\s*=(?!=))\\s*(true|false)");
    private static final Pattern LIGHT_NAV = Pattern.compile("(?:setAppearanceLightNavigationBars\\s*\\(|\\bisAppearanceLightNavigationBars\\s*=(?!=))\\s*(true|false)");
    private static final Pattern FULLSCREEN_ADD = Pattern.compile("(?:addFlags|setFlags)\\s*\\([^;]*FLAG_FULLSCREEN");
    private static final Pattern FULLSCREEN_CLEAR = Pattern.compile("clearFlags\\s*\\([^;]*FLAG_FULLSCREEN");
    private static final Pattern SYSTEM_UI = Pattern.compile("(?:setSystemUiVisibility\\s*\\(|\\bsystemUiVisibility\\s*=(?!=))([^;\\n]*)");
    private static final Pattern HIDE_SHOW = Pattern.compile("\\.\\s*(hide|show)\\s*\\(([^;\\n]*)\\)");
    private static final Pattern ACTION_BAR = Pattern.compile("(?:getSupportActionBar\\s*\\(\\s*\\)|\\bsupportActionBar|getActionBar\\s*\\(\\s*\\)|\\bactionBar)\\s*(?:\\?\\.|!!\\.|\\.)\\s*(hide|show)\\s*\\(");
    private static final Pattern NO_TITLE = Pattern.compile("(?:supportRequestWindowFeature|requestWindowFeature)\\s*\\([^;]*FEATURE_NO_TITLE");
    private static final Pattern HOME_AS_UP = Pattern.compile("(?:setDisplayHomeAsUpEnabled\\s*\\(|\\bdisplayHomeAsUpEnabled\\s*=(?!=))\\s*true");
    private static final Pattern EDGE_ON = Pattern.compile("\\benableEdgeToEdge\\s*\\(|setDecorFitsSystemWindows\\s*\\([^;]*\\bfalse\\b");

    static CodeScan scan(File source, Resources res, Resources.Theme theme, String[] packages) {
        CodeScan result = new CodeScan();
        if (source == null || !source.isFile()) return result;
        String text;
        try { text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8); } catch (Exception unreadable) { return result; }
        String name = source.getName();

        scanColor(text, name, SET_STATUS_COLOR, ASSIGN_STATUS_COLOR, res, theme, packages, true, result);
        scanColor(text, name, SET_NAV_COLOR, ASSIGN_NAV_COLOR, res, theme, packages, false, result);
        result.lightStatus = lastBoolean(LIGHT_STATUS, text, name);
        result.lightNav = lastBoolean(LIGHT_NAV, text, name);
        Matcher edge = EDGE_ON.matcher(text);
        while (edge.find()) result.edgeToEdge = new Found<>(true, where(name, text, edge.start()), edge.start());
        // These flags describe the system's transient-bars interaction even when written across lines.
        if (text.contains("SYSTEM_UI_FLAG_IMMERSIVE")
                || text.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE")) result.transientBars = true;

        // Full screen: FLAG_FULLSCREEN and SYSTEM_UI_FLAG_* decide the status bar, hide()/show() with WindowInsets types decide both.
        Matcher add = FULLSCREEN_ADD.matcher(text);
        while (add.find()) {
            result.statusVisibility.add(new Found<>(false, where(name, text, add.start()), add.start()));
            result.transientBars = true;
        }
        Matcher clear = FULLSCREEN_CLEAR.matcher(text);
        while (clear.find()) result.statusVisibility.add(new Found<>(true, where(name, text, clear.start()), clear.start()));
        Matcher ui = SYSTEM_UI.matcher(text);
        while (ui.find()) {
            String flags = ui.group(1);
            String at = where(name, text, ui.start());
            if (flags.contains("SYSTEM_UI_FLAG_FULLSCREEN")
                    || flags.contains("SYSTEM_UI_FLAG_HIDE_NAVIGATION")
                    || flags.contains("SYSTEM_UI_FLAG_IMMERSIVE")) result.transientBars = true;
            result.statusVisibility.add(new Found<>(!flags.contains("SYSTEM_UI_FLAG_FULLSCREEN"), at, ui.start()));
            result.navVisibility.add(new Found<>(!flags.contains("SYSTEM_UI_FLAG_HIDE_NAVIGATION"), at, ui.start()));
            if (flags.contains("SYSTEM_UI_FLAG_LIGHT_STATUS_BAR")) result.lightStatus = new Found<>(true, at, ui.start());
            if (flags.contains("SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR")) result.lightNav = new Found<>(true, at, ui.start());
        }
        Matcher hs = HIDE_SHOW.matcher(text);
        while (hs.find()) {
            String types = hs.group(2); boolean visible = hs.group(1).equals("show"); String at = where(name, text, hs.start());
            if (types.contains("systemBars")) {
                result.statusVisibility.add(new Found<>(visible, at, hs.start()));
                result.navVisibility.add(new Found<>(visible, at, hs.start()));
                if (!visible) result.transientBars = true;
            } else if (types.contains("statusBars")) {
                result.statusVisibility.add(new Found<>(visible, at, hs.start()));
                if (!visible) result.transientBars = true;
            } else if (types.contains("navigationBars")) {
                result.navVisibility.add(new Found<>(visible, at, hs.start()));
                if (!visible) result.transientBars = true;
            }
        }
        Matcher bar = ACTION_BAR.matcher(text);
        while (bar.find()) result.actionBarVisible = new Found<>(bar.group(1).equals("show"), where(name, text, bar.start()), bar.start());
        Matcher up = HOME_AS_UP.matcher(text);
        while (up.find()) result.homeAsUp = new Found<>(true, where(name, text, up.start()), up.start());
        Matcher noTitle = NO_TITLE.matcher(text);
        while (noTitle.find()) result.noTitleFeature = new Found<>(true, where(name, text, noTitle.start()), noTitle.start());
        return result;
    }

    private static void scanColor(String text, String file, Pattern call, Pattern assign, Resources res, Resources.Theme theme, String[] packages, boolean status, CodeScan out) {
        int bestPosition = -1; String expression = null;
        Matcher m = call.matcher(text);
        while (m.find()) { String argument = balanced(text, m.end() - 1); if (argument != null && m.start() > bestPosition) { bestPosition = m.start(); expression = argument; } }
        Matcher a = assign.matcher(text);
        while (a.find()) { if (a.start() > bestPosition) { bestPosition = a.start(); expression = a.group(1); } }
        if (expression == null) return;
        String at = where(file, text, bestPosition);
        Integer value = evalColor(expression.trim(), res, theme, packages);
        if (value != null) { if (status) out.statusColor = new Found<>(value, at, bestPosition); else out.navColor = new Found<>(value, at, bestPosition); }
        else if (status) { out.statusColorDynamic = true; out.statusDynamicWhere = at; } else { out.navColorDynamic = true; out.navDynamicWhere = at; }
    }

    private static Found<Boolean> lastBoolean(Pattern pattern, String text, String file) {
        Matcher m = pattern.matcher(text); Found<Boolean> last = null;
        while (m.find()) last = new Found<>(m.group(1).equals("true"), where(file, text, m.start()), m.start());
        return last;
    }

    /** Text between the parenthesis that starts at {@code open} and its matching closing one, or null. */
    private static String balanced(String text, int open) {
        if (open < 0 || open >= text.length() || text.charAt(open) != '(') return null;
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return text.substring(open + 1, i);
            else if (c == ';' && depth <= 1) return null;
        }
        return null;
    }

    private static String where(String file, String text, int position) {
        int line = 1;
        for (int i = 0; i < position && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return file + " line " + line;
    }

    /** Colors that can be read without running code. Anything that depends on a variable or a calculation returns null. */
    static Integer evalColor(String expression, Resources res, Resources.Theme theme, String[] packages) {
        try {
            Matcher m = ANDROID_COLOR.matcher(expression);
            if (m.find()) { int id = res.getIdentifier(m.group(1), "color", "android"); if (id != 0) return res.getColor(id, theme); }
            m = APP_COLOR.matcher(expression);
            if (m.find()) { int id = ProjectPreview.findIdentifier(res, m.group(1), "color", packages); return id == 0 ? null : res.getColor(id, theme); }
            m = PARSE_COLOR.matcher(expression);
            if (m.find()) return Color.parseColor(m.group(1));
            m = HEX.matcher(expression);
            if (m.find()) { long v = Long.parseLong(m.group(1), 16); return (int) (m.group(1).length() == 6 ? 0xFF000000L | v : v); }
            m = ARGB.matcher(expression);
            if (m.find()) return Color.argb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
            m = RGB.matcher(expression);
            if (m.find()) return Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            m = NAMED.matcher(expression);
            if (m.find()) {
                switch (m.group(1)) {
                    case "RED": return Color.RED; case "BLUE": return Color.BLUE; case "GREEN": return Color.GREEN; case "BLACK": return Color.BLACK;
                    case "WHITE": return Color.WHITE; case "TRANSPARENT": return Color.TRANSPARENT; case "GRAY": return Color.GRAY; case "DKGRAY": return Color.DKGRAY;
                    case "LTGRAY": return Color.LTGRAY; case "YELLOW": return Color.YELLOW; case "CYAN": return Color.CYAN; default: return Color.MAGENTA;
                }
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ resolving

    /**
     * @param theme      the project's own theme, created from its compiled resources (null when the resources could not be built)
     * @param code       what the Activity source says (may be an empty scan)
     * @param themeName  name of the theme for the tips, for example "AppTheme"
     */
    static PreviewBars resolve(Resources res, Resources.Theme theme, String themeName, CodeScan code, int targetSdk, String title, String[] packages) {
        PreviewBars bars = new PreviewBars();
        bars.title = title;
        systemDefaults(bars);
        String themeLabel = "theme " + (themeName == null ? "(default)" : themeName);
        if (theme != null) {
            Integer sc = themeColor(res, theme, android.R.attr.statusBarColor); if (sc != null) bars.statusColor = sc;
            Integer nc = themeColor(res, theme, android.R.attr.navigationBarColor); if (nc != null) bars.navColor = nc;
            bars.statusInfo = "statusBarColor = " + hex(bars.statusColor) + resourceNote(res, theme, android.R.attr.statusBarColor) + "\nFrom " + themeLabel;
            bars.navInfo = "navigationBarColor = " + hex(bars.navColor) + resourceNote(res, theme, android.R.attr.navigationBarColor) + "\nFrom " + themeLabel;
            bars.lightStatus = themeBool(theme, android.R.attr.windowLightStatusBar, false);
            bars.lightNav = themeBool(theme, android.R.attr.windowLightNavigationBar, false);
            bars.showStatus = !themeBool(theme, android.R.attr.windowFullscreen, false);
            bars.edgeToEdge = targetSdk >= 35 && !themeBool(theme, android.R.attr.windowOptOutEdgeToEdgeEnforcement, false);

            Boolean platformBar = themeBoolOrNull(theme, android.R.attr.windowActionBar);
            Boolean platformNoTitle = themeBoolOrNull(theme, android.R.attr.windowNoTitle);
            int appBar = ProjectPreview.findIdentifier(res, "windowActionBar", "attr", packages);
            int appNoTitle = ProjectPreview.findIdentifier(res, "windowNoTitle", "attr", packages);
            Boolean compatBar = appBar == 0 ? null : themeBoolOrNull(theme, appBar);
            Boolean compatNoTitle = appNoTitle == 0 ? null : themeBoolOrNull(theme, appNoTitle);
            boolean hasBar = compatBar != null ? compatBar : platformBar != null ? platformBar : true;
            boolean noTitle = compatNoTitle != null ? compatNoTitle : platformNoTitle != null && platformNoTitle;
            bars.actionBar = hasBar && !noTitle;
            int primaryId = ProjectPreview.findIdentifier(res, "colorPrimary", "attr", packages);
            Integer primary = primaryId != 0 ? themeColor(res, theme, primaryId) : null;
            if (primary == null) primary = themeColor(res, theme, android.R.attr.colorPrimary);
            if (primary != null) bars.actionBarColor = primary;
            Integer barBackground = actionBarBackground(res, theme);
            if (barBackground != null) bars.actionBarColor = barBackground;
            bars.actionBarInfo = "colorPrimary = " + hex(bars.actionBarColor) + resourceNote(res, theme, primaryId != 0 ? primaryId : android.R.attr.colorPrimary) + "\nAction bar from " + themeLabel;
        } else {
            bars.statusInfo = "statusBarColor = " + hex(bars.statusColor) + "\nNo theme could be read, default value";
            bars.navInfo = "navigationBarColor = " + hex(bars.navColor) + "\nNo theme could be read, default value";
            bars.edgeToEdge = targetSdk >= 35;
        }

        // The Activity source runs after the theme is applied, so what it says wins.
        if (code != null) {
            if (code.statusColor != null) { bars.statusColor = code.statusColor.value; bars.statusInfo = "statusBarColor = " + hex(code.statusColor.value) + "\nFrom " + code.statusColor.where; }
            else if (code.statusColorDynamic) bars.statusInfo += "\nThe code sets statusBarColor at " + code.statusDynamicWhere + " but the value is not readable (variable or calculation).";
            if (code.navColor != null) { bars.navColor = code.navColor.value; bars.navInfo = "navigationBarColor = " + hex(code.navColor.value) + "\nFrom " + code.navColor.where; }
            else if (code.navColorDynamic) bars.navInfo += "\nThe code sets navigationBarColor at " + code.navDynamicWhere + " but the value is not readable (variable or calculation).";
            if (code.lightStatus != null) { bars.lightStatus = code.lightStatus.value; bars.statusInfo += "\nDark icons = " + code.lightStatus.value + " (" + code.lightStatus.where + ")"; }
            if (code.lightNav != null) { bars.lightNav = code.lightNav.value; bars.navInfo += "\nDark icons = " + code.lightNav.value + " (" + code.lightNav.where + ")"; }
            if (code.edgeToEdge != null) { bars.edgeToEdge = true; }
            bars.transientBars = code.transientBars;
            Boolean status = lastVisibility(code.statusVisibility); if (status != null) bars.showStatus = status;
            Boolean nav = lastVisibility(code.navVisibility); if (nav != null) bars.showNav = nav;
            if (code.actionBarVisible != null && !code.actionBarVisible.value) bars.actionBar = false;
            if (code.noTitleFeature != null) bars.actionBar = false;
            if (code.homeAsUp != null) bars.homeAsUp = true;
        }
        // Hidden bars remain recoverable in the preview through an inward swipe from that screen edge.
        // This is a static preview approximation; user Java is read, not executed.
        if (!bars.showStatus || !bars.showNav) bars.transientBars = true;
        if (bars.edgeToEdge) {
            bars.statusInfo += "\nEdge-to-edge (targetSdk " + targetSdk + "+): the bar is transparent and the content draws underneath, so the color above has no effect.";
            bars.navInfo += "\nEdge-to-edge (targetSdk " + targetSdk + "+): the bar is transparent and the content draws underneath, so the color above has no effect.";
        }
        return bars;
    }

    /** Values of the Android default theme (Theme.DeviceDefault), the base every app starts from. Nothing here is a made-up color. */
    private static void systemDefaults(PreviewBars bars) {
        Resources system = Resources.getSystem();
        Resources.Theme base = system.newTheme();
        base.applyStyle(android.R.style.Theme_DeviceDefault, true);
        Integer status = themeColor(system, base, android.R.attr.statusBarColor);
        Integer nav = themeColor(system, base, android.R.attr.navigationBarColor);
        Integer primary = themeColor(system, base, android.R.attr.colorPrimary);
        bars.statusColor = status != null ? status : Color.TRANSPARENT;
        bars.navColor = nav != null ? nav : Color.TRANSPARENT;
        bars.actionBarColor = primary != null ? primary : Color.TRANSPARENT;
    }

    /** Background of the action bar as the theme's actionBarStyle declares it; null when it is not a plain color. */
    private static Integer actionBarBackground(Resources res, Resources.Theme theme) {
        TypedValue style = new TypedValue();
        try {
            if (!theme.resolveAttribute(android.R.attr.actionBarStyle, style, true) || style.resourceId == 0) return null;
            android.content.res.TypedArray array = theme.obtainStyledAttributes(style.resourceId, new int[] {android.R.attr.background});
            try {
                TypedValue background = new TypedValue();
                if (!array.getValue(0, background)) return null;
                if (background.type >= TypedValue.TYPE_FIRST_COLOR_INT && background.type <= TypedValue.TYPE_LAST_COLOR_INT) return background.data;
                if (background.resourceId != 0) {
                    String type = res.getResourceTypeName(background.resourceId);
                    if ("color".equals(type)) return res.getColor(background.resourceId, theme);
                }
            } finally { array.recycle(); }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static Boolean lastVisibility(List<Found<Boolean>> events) {
        Found<Boolean> last = null;
        for (Found<Boolean> event : events) if (last == null || event.position > last.position) last = event;
        return last == null ? null : last.value;
    }

    private static Integer themeColor(Resources res, Resources.Theme theme, int attr) {
        TypedValue value = new TypedValue();
        try {
            if (!theme.resolveAttribute(attr, value, true)) return null;
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
            if (value.resourceId != 0) return res.getColor(value.resourceId, theme);
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static Boolean themeBoolOrNull(Resources.Theme theme, int attr) {
        TypedValue value = new TypedValue();
        try {
            if (theme.resolveAttribute(attr, value, true) && value.type == TypedValue.TYPE_INT_BOOLEAN) return value.data != 0;
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static boolean themeBool(Resources.Theme theme, int attr, boolean fallback) {
        Boolean value = themeBoolOrNull(theme, attr);
        return value == null ? fallback : value;
    }

    /** " (@color/blue_700)" when the theme value points at a resource, else an empty string. */
    private static String resourceNote(Resources res, Resources.Theme theme, int attr) {
        TypedValue value = new TypedValue();
        try {
            if (theme.resolveAttribute(attr, value, true) && value.resourceId != 0) return " (@" + res.getResourceTypeName(value.resourceId) + "/" + res.getResourceEntryName(value.resourceId) + ")";
        } catch (RuntimeException ignored) { }
        return "";
    }

    static String hex(int color) {
        return (color >>> 24) == 0xFF ? String.format("#%06X", color & 0xFFFFFF) : String.format("#%08X", color);
    }
}
