package com.example.studiomobile.workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out, by reading the Activity source (never running it), what a tap on a view does:
 * open another Activity of the project, show a Toast, or close the screen.
 * This is what lets the preview move from page A to page B and come back, like the real app.
 * Pure Java, no Android classes, so it can be unit tested on the JVM.
 */
final class PreviewLinks {
    private PreviewLinks() { }

    /** What a click does. Null fields mean "nothing of that kind found". */
    static final class Action {
        /** Simple class name of the Activity that is started, or null. */
        String activity;
        /** Toast text: a literal, or "@string/name" when the code uses R.string.name. */
        String toast;
        /** The click closes the screen (finish() or onBackPressed()). */
        boolean finish;
        /** Changes the code makes to views when the click happens (visibility, text, colors, ...), in the order they are written. */
        final List<Effect> effects = new ArrayList<>();
        @Override public String toString() { return "Action{activity=" + activity + ", toast=" + toast + ", finish=" + finish + ", effects=" + effects + "}"; }
    }

    /**
     * One change to a view: {@code view.setVisibility(View.GONE)}, {@code view.text = "Hi"}, ...
     * kind: visibility | text | textColor | background | enabled | checked | clickable | alpha.
     * value: visibility = visible/invisible/gone/toggle; text = literal or "@string/name"; textColor and background = "#AARRGGBB" or "@color/name";
     * enabled, checked, clickable = true/false; alpha = a float as text.
     */
    static final class Effect {
        final String target, kind, value;
        Effect(String target, String kind, String value) { this.target = target; this.kind = kind; this.value = value; }
        @Override public String toString() { return target + "." + kind + "=" + value; }
    }

    private static final Pattern LAYOUT_ID = Pattern.compile("@\\+?id/(\\w+)");
    private static final Pattern VAR_FROM_FIND = Pattern.compile("\\b(\\w+)\\s*(?::\\s*[\\w.<>?]+\\s*)?=\\s*(?:\\([^)]*\\)\\s*)?(?:[\\w.]+\\s*\\.\\s*)?findViewById\\s*(?:<[^>]*>)?\\s*\\(\\s*R\\s*\\.\\s*id\\s*\\.\\s*(\\w+)\\s*\\)");
    private static final Pattern CLASS_REF = Pattern.compile("(\\w+)\\s*(?:\\.class\\b|::class\\b)");
    private static final Pattern FINISH = Pattern.compile("(?<![\\w.])(?:finish|onBackPressed)\\s*\\(\\s*\\)|\\.\\s*(?:finish|onBackPressed)\\s*\\(\\s*\\)");
    private static final Pattern XML_TAG = Pattern.compile("<\\w[^<>]*>");
    private static final Pattern ON_CLICK_ATTR = Pattern.compile("android:onClick\\s*=\\s*\"(\\w+)\"");

    /**
     * @param code        Java or Kotlin source of the Activity
     * @param layoutXml   the layout the Activity shows (may be empty)
     * @param activities  simple names of every Activity class of the project
     * @return view id name (as written after R.id.) to the action of its click
     */
    static Map<String, Action> scan(String code, String layoutXml, Set<String> activities) {
        Map<String, Action> result = new LinkedHashMap<>();
        if (code == null || code.isEmpty()) return result;
        if (layoutXml == null) layoutXml = "";
        List<String> layoutIds = new ArrayList<>();
        Matcher ids = LAYOUT_ID.matcher(layoutXml);
        while (ids.find()) if (!layoutIds.contains(ids.group(1))) layoutIds.add(ids.group(1));

        Map<String, String> variables = new LinkedHashMap<>();
        Matcher vars = VAR_FROM_FIND.matcher(code);
        while (vars.find()) variables.put(vars.group(1), vars.group(2));

        // view.setOnClickListener(...)  /  findViewById(R.id.x).setOnClickListener(...)  /  binding.x.setOnClickListener { ... }
        int from = 0;
        while (true) {
            int at = code.indexOf("setOnClickListener", from);
            if (at < 0) break;
            from = at + "setOnClickListener".length();
            int dot = previousNonSpace(code, at - 1);
            if (dot < 0 || code.charAt(dot) != '.') continue;
            String id = receiverId(code, dot - 1, variables, layoutIds);
            if (id == null) continue;
            int open = nextNonSpace(code, from);
            if (open < 0) continue;
            String body = null;
            if (code.charAt(open) == '(') body = balanced(code, open, '(', ')');
            else if (code.charAt(open) == '{') body = balanced(code, open, '{', '}');
            Action action = actionOf(body, activities, variables, layoutIds);
            if (action != null) result.put(id, action);
        }

        return result;
    }


    /**
     * Actions of the views that declare android:onClick="method" in the layout, in the order the tags appear in the XML
     * (null for a method that does nothing the preview can show). The inflated views are visited in the same order,
     * which also covers views without an id.
     */
    static List<Action> onClickActions(String code, String layoutXml, Set<String> activities) {
        List<Action> result = new ArrayList<>();
        if (layoutXml == null) return result;
        List<String> layoutIds = idsOf(layoutXml);
        Map<String, String> variables = variablesOf(code);
        Matcher tag = XML_TAG.matcher(layoutXml);
        while (tag.find()) {
            Matcher attr = ON_CLICK_ATTR.matcher(tag.group());
            if (!attr.find()) continue;
            result.add(code == null ? null : actionOf(methodBody(code, attr.group(1)), activities, variables, layoutIds));
        }
        return result;
    }

    // ------------------------------------------------------------------ receivers

    /** Id name of the view that a call like "x.setOnClickListener" is made on; {@code end} is the index just before the dot. */
    private static String receiverId(String code, int end, Map<String, String> variables, List<String> layoutIds) {
        end = previousNonSpace(code, end);
        if (end < 0) return null;
        if (code.charAt(end) == ')') {
            int open = matchingOpen(code, end);
            if (open < 0) return null;
            Matcher id = Pattern.compile("R\\s*\\.\\s*id\\s*\\.\\s*(\\w+)").matcher(code.substring(open, end + 1));
            return id.find() ? id.group(1) : null;
        }
        int start = end;
        while (start >= 0 && (Character.isLetterOrDigit(code.charAt(start)) || code.charAt(start) == '_')) start--;
        String name = code.substring(start + 1, end + 1);
        if (name.isEmpty()) return null;
        String viaVariable = variables.get(name);
        if (viaVariable != null) return viaVariable;
        String wanted = normalize(name);
        for (String id : layoutIds) if (normalize(id).equals(wanted)) return id;   // btnOpen (view binding) matches btn_open
        return null;
    }

    private static String normalize(String name) { return name.replace("_", "").toLowerCase(java.util.Locale.ROOT); }

    // ------------------------------------------------------------------ what a click does

    static Action actionOf(String body, Set<String> activities) {
        return actionOf(body, activities, Collections.<String, String>emptyMap(), Collections.<String>emptyList());
    }

    static Action actionOf(String body, Set<String> activities, Map<String, String> variables, List<String> layoutIds) {
        if (body == null || body.isEmpty()) return null;
        Action action = new Action();
        if (body.contains("startActivity")) {
            Matcher classes = CLASS_REF.matcher(body);
            while (classes.find()) if (activities.contains(classes.group(1))) { action.activity = classes.group(1); break; }
        }
        int toast = body.indexOf("Toast.makeText");
        if (toast < 0) toast = body.indexOf("Toast .makeText");
        if (toast >= 0) {
            int open = body.indexOf('(', toast);
            String args = open < 0 ? null : balanced(body, open, '(', ')');
            if (args != null) action.toast = toastText(args);
        }
        action.finish = FINISH.matcher(body).find();
        action.effects.addAll(clickEffects(body, variables, layoutIds));
        return action.activity == null && action.toast == null && !action.finish && action.effects.isEmpty() ? null : action;
    }

    // ------------------------------------------------------------------ changes to views

    private static final Pattern JAVA_SETTER = Pattern.compile("\\.\\s*(setVisibility|setText|setTextColor|setBackgroundColor|setEnabled|setAlpha|setChecked|setClickable)\\s*\\(");
    private static final Pattern KOTLIN_PROPERTY = Pattern.compile("\\.\\s*(visibility|text|textColor|backgroundColor|isVisible|isGone|isInvisible|isEnabled|isChecked|isClickable|alpha)\\s*=(?!=)\\s*([^;\\n]+)");
    private static final Pattern VISIBILITY_CHECK = Pattern.compile("getVisibility\\s*\\(|\\bisVisible\\b|\\bisGone\\b|\\bisInvisible\\b|\\.\\s*visibility\\s*(?:==|!=)");
    private static final Pattern CONTROL_WORD = Pattern.compile("\\b(if|else|for|while|switch|try|catch|finally|when)\\b|\\bset\\w*Listener\\b|\\b(?:postDelayed|post|runOnUiThread|launch|observe|subscribe|enqueue)\\b|\\bnew\\s+[\\w.<>]+\\s*\\(\\s*\\)\\s*\\{");

    /** Ids written as @+id/name or @id/name in a layout, in order of appearance. */
    static List<String> idsOf(String layoutXml) {
        List<String> ids = new ArrayList<>();
        if (layoutXml == null) return ids;
        Matcher m = LAYOUT_ID.matcher(layoutXml);
        while (m.find()) if (!ids.contains(m.group(1))) ids.add(m.group(1));
        return ids;
    }

    /** Variables assigned from findViewById(R.id.x) in the code: variable name to id name. */
    static Map<String, String> variablesOf(String code) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (code == null) return variables;
        Matcher vars = VAR_FROM_FIND.matcher(code);
        while (vars.find()) variables.put(vars.group(1), vars.group(2));
        return variables;
    }

    /**
     * Effects of a click handler. Only statements that always run are used; the exception is a visibility switch written as
     * "if (view is visible) hide else show" (or a ternary, or {@code !isVisible}), which becomes a toggle.
     */
    static List<Effect> clickEffects(String body, Map<String, String> variables, List<String> layoutIds) {
        List<Effect> sure = collectEffects(withoutBranches(body), variables, layoutIds);
        List<Effect> all = collectEffects(body, variables, layoutIds);
        if (!VISIBILITY_CHECK.matcher(body).find()) return sure;
        Map<String, Set<String>> seen = new LinkedHashMap<>();
        for (Effect e : all) if (e.kind.equals("visibility")) {
            Set<String> values = seen.get(e.target);
            if (values == null) { values = new LinkedHashSet<>(); seen.put(e.target, values); }
            values.add(e.value);
        }
        List<Effect> result = new ArrayList<>();
        Set<String> toggled = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : seen.entrySet()) if (entry.getValue().size() > 1 || entry.getValue().contains("toggle")) toggled.add(entry.getKey());
        for (Effect e : sure) if (!(e.kind.equals("visibility") && toggled.contains(e.target))) result.add(e);
        for (String target : toggled) result.add(new Effect(target, "visibility", "toggle"));
        return result;
    }

    /**
     * Effects of the statements in onCreate / onStart / onResume that always run (listeners, callbacks and branches are left out),
     * so the preview starts the way the screen looks after those methods ran.
     */
    static List<Effect> startupEffects(String code, String layoutXml) {
        List<Effect> result = new ArrayList<>();
        if (code == null || code.isEmpty()) return result;
        List<String> layoutIds = idsOf(layoutXml);
        Map<String, String> variables = variablesOf(code);
        for (String method : new String[] {"onCreate", "onStart", "onResume"}) {
            Matcher m = Pattern.compile("(?:\\bvoid|\\bfun)\\s+" + method + "\\s*\\(").matcher(code);
            if (!m.find()) continue;
            int paren = code.indexOf('(', m.start());
            String params = paren < 0 ? null : balanced(code, paren, '(', ')');
            int open = params == null ? -1 : code.indexOf('{', paren + params.length() + 2);
            String body = open < 0 ? null : balanced(code, open, '{', '}');
            if (body != null) result.addAll(collectEffects(withoutBranches(body), variables, layoutIds));
        }
        return result;
    }

    /** Removes if/else/loops/try/when blocks, listener and callback arguments, anonymous classes: whatever may or may not run. */
    static String withoutBranches(String body) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        Matcher m = CONTROL_WORD.matcher(body);
        while (pos < body.length() && m.find(pos)) {
            out.append(body, pos, m.start());
            String word = m.group();
            int end = m.end();
            if (word.startsWith("new ")) { int open = body.indexOf('{', m.start()); String block = open < 0 ? null : balanced(body, open, '{', '}'); pos = block == null ? body.length() : open + block.length() + 2; continue; }
            int next = nextNonSpace(body, end);
            boolean control = m.group(1) != null;
            if (control && word.equals("else") && next >= 0 && body.startsWith("if", next)) { pos = end; continue; }
            if (next >= 0 && body.charAt(next) == '(') {
                String args = balanced(body, next, '(', ')');
                if (args == null) { pos = body.length(); break; }
                end = next + args.length() + 2;
                next = nextNonSpace(body, end);
            }
            if (next >= 0 && body.charAt(next) == '{') {
                String block = balanced(body, next, '{', '}');
                pos = block == null ? body.length() : next + block.length() + 2;
            } else if (control) {
                int stop = end;
                while (stop < body.length() && body.charAt(stop) != ';' && body.charAt(stop) != '\n') stop++;
                pos = Math.min(body.length(), stop + 1);
            } else pos = end;
        }
        if (pos < body.length()) out.append(body, pos, body.length());
        return out.toString();
    }

    private static List<Effect> collectEffects(String body, Map<String, String> variables, List<String> layoutIds) {
        List<Effect> found = new ArrayList<>();
        List<int[]> order = new ArrayList<>();   // {position, index in found}
        Matcher setter = JAVA_SETTER.matcher(body);
        while (setter.find()) {
            int open = setter.end() - 1;
            String args = balanced(body, open, '(', ')');
            String id = receiverId(body, setter.start() - 1, variables, layoutIds);
            if (args == null || id == null) continue;
            Effect effect = effectFor(id, setter.group(1), args.trim());
            if (effect != null) { order.add(new int[] {setter.start(), found.size()}); found.add(effect); }
        }
        Matcher property = KOTLIN_PROPERTY.matcher(body);
        while (property.find()) {
            String id = receiverId(body, property.start() - 1, variables, layoutIds);
            if (id == null) continue;
            Effect effect = effectFor(id, property.group(1), property.group(2).trim());
            if (effect != null) { order.add(new int[] {property.start(), found.size()}); found.add(effect); }
        }
        java.util.Collections.sort(order, (a, b) -> Integer.compare(a[0], b[0]));
        List<Effect> result = new ArrayList<>();
        for (int[] entry : order) result.add(found.get(entry[1]));
        return result;
    }

    /** One effect from the name of the setter or property and its argument text; null when the value cannot be known without running the code. */
    private static Effect effectFor(String id, String name, String arg) {
        if (arg.endsWith(")") && arg.startsWith("(") && balanced(arg, 0, '(', ')') != null && balanced(arg, 0, '(', ')').length() == arg.length() - 2) arg = arg.substring(1, arg.length() - 1).trim();
        switch (name) {
            case "setVisibility": case "visibility": {
                String value = visibilityValue(arg, id);
                return value == null ? null : new Effect(id, "visibility", value);
            }
            case "isVisible": case "isGone": case "isInvisible": {
                if (arg.startsWith("!") && VISIBILITY_CHECK.matcher(arg).find()) return new Effect(id, "visibility", "toggle");
                Boolean flag = bool(arg);
                if (flag == null) return null;
                if (name.equals("isVisible")) return new Effect(id, "visibility", flag ? "visible" : "gone");
                if (name.equals("isGone")) return new Effect(id, "visibility", flag ? "gone" : "visible");
                return new Effect(id, "visibility", flag ? "invisible" : "visible");
            }
            case "setText": case "text": {
                String text = textValue(arg);
                return text == null ? null : new Effect(id, "text", text);
            }
            case "setTextColor": case "textColor": { String c = colorValue(arg); return c == null ? null : new Effect(id, "textColor", c); }
            case "setBackgroundColor": case "backgroundColor": { String c = colorValue(arg); return c == null ? null : new Effect(id, "background", c); }
            case "setEnabled": case "isEnabled": { Boolean b = bool(arg); return b == null ? null : new Effect(id, "enabled", String.valueOf(b)); }
            case "setChecked": case "isChecked": { Boolean b = bool(arg); return b == null ? null : new Effect(id, "checked", String.valueOf(b)); }
            case "setClickable": case "isClickable": { Boolean b = bool(arg); return b == null ? null : new Effect(id, "clickable", String.valueOf(b)); }
            case "setAlpha": case "alpha": {
                try { return new Effect(id, "alpha", String.valueOf(Float.parseFloat(arg.replaceAll("[fF]$", "")))); }
                catch (NumberFormatException unknown) { return null; }
            }
            default: return null;
        }
    }

    private static String visibilityValue(String arg, String id) {
        boolean gone = arg.contains("GONE"), invisible = arg.contains("INVISIBLE"), visible = arg.replace("INVISIBLE", "").contains("VISIBLE");
        int kinds = (gone ? 1 : 0) + (invisible ? 1 : 0) + (visible ? 1 : 0);
        if (kinds == 0) return null;
        if (kinds > 1) return VISIBILITY_CHECK.matcher(arg).find() ? "toggle" : null;
        return gone ? "gone" : invisible ? "invisible" : "visible";
    }

    private static Boolean bool(String arg) {
        String v = arg.trim();
        return v.equals("true") ? Boolean.TRUE : v.equals("false") ? Boolean.FALSE : null;
    }

    private static String textValue(String arg) {
        if (arg.indexOf('+') >= 0 && !arg.startsWith("\"")) return null;
        if (arg.length() >= 2 && arg.startsWith("\"") && arg.indexOf('"', 1) == arg.length() - 1) return arg.substring(1, arg.length() - 1).replace("\\n", "\n");
        Matcher res = Pattern.compile("R\\s*\\.\\s*string\\s*\\.\\s*(\\w+)").matcher(arg);
        return res.find() ? "@string/" + res.group(1) : null;
    }

    private static final Map<String, String> NAMED_COLORS = new LinkedHashMap<>();
    static {
        String[][] named = {{"RED", "#FFFF0000"}, {"BLUE", "#FF0000FF"}, {"GREEN", "#FF00FF00"}, {"BLACK", "#FF000000"}, {"WHITE", "#FFFFFFFF"}, {"TRANSPARENT", "#00000000"},
            {"GRAY", "#FF888888"}, {"DKGRAY", "#FF444444"}, {"LTGRAY", "#FFCCCCCC"}, {"YELLOW", "#FFFFFF00"}, {"CYAN", "#FF00FFFF"}, {"MAGENTA", "#FFFF00FF"}};
        for (String[] pair : named) NAMED_COLORS.put(pair[0], pair[1]);
    }

    /** "#AARRGGBB" or "@color/name" for the color expressions the code commonly uses; null when it is computed. */
    static String colorValue(String arg) {
        String a = arg.trim();
        Matcher parse = Pattern.compile("Color\\s*\\.\\s*parseColor\\s*\\(\\s*\"#([0-9a-fA-F]{3,8})\"\\s*\\)").matcher(a);
        if (parse.find()) return hexColor(parse.group(1));
        Matcher hex = Pattern.compile("\\b0[xX]([0-9a-fA-F]{6,8})\\b").matcher(a);
        if (hex.find()) return hexColor(hex.group(1));
        Matcher rgb = Pattern.compile("Color\\s*\\.\\s*rgb\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").matcher(a);
        if (rgb.find()) return argb(255, Integer.parseInt(rgb.group(1)), Integer.parseInt(rgb.group(2)), Integer.parseInt(rgb.group(3)));
        Matcher argb = Pattern.compile("Color\\s*\\.\\s*argb\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").matcher(a);
        if (argb.find()) return argb(Integer.parseInt(argb.group(1)), Integer.parseInt(argb.group(2)), Integer.parseInt(argb.group(3)), Integer.parseInt(argb.group(4)));
        Matcher res = Pattern.compile("(?<![\\w.])R\\s*\\.\\s*color\\s*\\.\\s*(\\w+)").matcher(a);
        if (res.find()) return "@color/" + res.group(1);
        Matcher named = Pattern.compile("\\bColor\\s*\\.\\s*([A-Z]+)\\b").matcher(a);
        if (named.find() && NAMED_COLORS.containsKey(named.group(1))) return NAMED_COLORS.get(named.group(1));
        return null;
    }

    private static String hexColor(String digits) {
        if (digits.length() == 3) digits = "" + digits.charAt(0) + digits.charAt(0) + digits.charAt(1) + digits.charAt(1) + digits.charAt(2) + digits.charAt(2);
        if (digits.length() == 6) return "#FF" + digits.toUpperCase(java.util.Locale.ROOT);
        if (digits.length() == 8) return "#" + digits.toUpperCase(java.util.Locale.ROOT);
        return null;
    }

    private static String argb(int a, int r, int g, int b) {
        return String.format(java.util.Locale.ROOT, "#%02X%02X%02X%02X", a & 255, r & 255, g & 255, b & 255);
    }

    /** The second argument of Toast.makeText(context, text, length). */
    private static String toastText(String args) {
        List<String> parts = splitTopLevel(args);
        if (parts.size() < 2) return null;
        String text = parts.get(1).trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"") && text.indexOf('"', 1) == text.length() - 1) return text.substring(1, text.length() - 1);
        Matcher res = Pattern.compile("R\\s*\\.\\s*string\\s*\\.\\s*(\\w+)").matcher(text);
        return res.find() ? "@string/" + res.group(1) : null;
    }

    private static String methodBody(String code, String method) {
        Matcher m = Pattern.compile("(?:\\bvoid|\\bfun)\\s+" + Pattern.quote(method) + "\\s*\\(").matcher(code);
        if (!m.find()) return null;
        int open = code.indexOf('{', m.end());
        return open < 0 ? null : balanced(code, open, '{', '}');
    }

    // ------------------------------------------------------------------ text helpers

    private static int previousNonSpace(String text, int index) {
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) index--;
        return index;
    }

    private static int nextNonSpace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index < text.length() ? index : -1;
    }

    /** Text between the bracket at {@code open} and its partner (string literals are skipped); null when it never closes. */
    static String balanced(String text, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { i = endOfString(text, i); if (i < 0) return null; continue; }
            if (c == opening) depth++;
            else if (c == closing && --depth == 0) return text.substring(open + 1, i);
        }
        return null;
    }

    private static int endOfString(String text, int quote) {
        for (int i = quote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') i++;
            else if (c == '"') return i;
            else if (c == '\n') return i;
        }
        return -1;
    }

    /** Index of the "(" that matches the ")" at {@code close}, or -1. */
    private static int matchingOpen(String text, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ')') depth++;
            else if (c == '(' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String args) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '"') { int end = endOfString(args, i); if (end < 0) break; i = end; continue; }
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) { parts.add(args.substring(start, i)); start = i + 1; }
        }
        parts.add(args.substring(start));
        return parts;
    }

    /** Names of all classes declared in the given sources, for matching "X.class" against the project's Activities. */
    static Set<String> simpleNames(Iterable<String> fileNames) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : fileNames) { int dot = name.lastIndexOf('.'); names.add(dot < 0 ? name : name.substring(0, dot)); }
        return names;
    }
}
