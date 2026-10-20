package com.example.studiomobile.workspace;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Xml;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;

/**
 * Design-time {@code tools:} attributes. aapt2 does not keep them in the compiled layout, so they are read from the layout source files:
 * tools:text, tools:hint, tools:src, tools:srcCompat, tools:background (and tools:listitem / tools:itemCount, used by the list stand-ins).
 * They only fill what the layout leaves empty, so the preview still shows what the built app shows. Views are matched by their android:id.
 */
final class PreviewTools {
    private PreviewTools() { }

    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android", TOOLS_NS = "http://schemas.android.com/tools";

    private static final String[] NAMES = {"Alex Johnson", "Maria Garcia", "Chen Wei", "Aisha Khan", "Liam Murphy", "Sofia Rossi", "Noah Kim", "Emma Dubois"};
    private static final String[] FIRST = {"Alex", "Maria", "Chen", "Aisha", "Liam", "Sofia", "Noah", "Emma"};
    private static final String[] LAST = {"Johnson", "Garcia", "Wei", "Khan", "Murphy", "Rossi", "Kim", "Dubois"};
    private static final String[] CITIES = {"Jakarta", "Tokyo", "Berlin", "Lagos", "Lima", "Oslo", "Cairo", "Toronto"};
    private static final String[] LOREM = {
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
        "Sed do eiusmod tempor incididunt ut labore et dolore magna.",
        "Ut enim ad minim veniam, quis nostrud exercitation.",
        "Duis aute irure dolor in reprehenderit in voluptate."
    };

    /** Tools attributes of every view that has an android:id, across all layout files of the project. */
    static final class Index {
        private final Map<String, Map<String, String>> byId = new HashMap<>();

        Map<String, String> forId(String idName) { return idName == null ? null : byId.get(idName); }

        static Index load(File resDir) {
            Index index = new Index();
            File[] dirs = resDir == null ? null : resDir.listFiles((dir, name) -> name.startsWith("layout"));
            if (dirs == null) return index;
            for (File dir : dirs) {
                File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
                if (files == null) continue;
                for (File file : files) index.scan(file);
            }
            return index;
        }

        private void scan(File file) {
            try (InputStream in = new FileInputStream(file)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                parser.setInput(in, null);
                for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                    if (event != XmlPullParser.START_TAG) continue;
                    String id = null;
                    Map<String, String> tools = null;
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        String ns = parser.getAttributeNamespace(i), name = parser.getAttributeName(i), value = parser.getAttributeValue(i);
                        if (TOOLS_NS.equals(ns)) {
                            if (tools == null) tools = new HashMap<>();
                            tools.put(name, value);
                        } else if (ANDROID_NS.equals(ns) && "id".equals(name) && value != null) {
                            int slash = value.indexOf('/');
                            id = slash >= 0 ? value.substring(slash + 1) : value;
                        }
                    }
                    if (id != null && tools != null && !byId.containsKey(id)) byId.put(id, tools);
                }
            } catch (Exception ignored) { /* a layout that cannot be read just has no tools attributes */ }
        }
    }

    static String idName(int id, PreviewStandIns.Env env) {
        if (id == View.NO_ID || id == 0) return null;
        try { return env.resources.getResourceEntryName(id); } catch (Resources.NotFoundException e) { return null; }
    }

    /** Fills empty text / hint / image / background from tools attributes. {@code seed} varies the sample data between list items. */
    static void apply(View view, PreviewStandIns.Env env, int seed) {
        if (view == null || view instanceof WebView) return;
        Map<String, String> tools = env.tools().forId(idName(view.getId(), env));
        if (tools != null) applyOne(view, tools, env, seed);
        if (view instanceof ViewGroup && !(view instanceof PreviewStandIns.Opaque)) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i), env, seed);
        }
    }

    private static void applyOne(View view, Map<String, String> tools, PreviewStandIns.Env env, int seed) {
        try {
            Context context = view.getContext();
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                String value = tools.get("text");
                if (value != null && (text.getText() == null || text.getText().length() == 0)) text.setText(sample(value, env, seed));
                String hint = tools.get("hint");
                if (hint != null && text.getHint() == null) text.setHint(sample(hint, env, seed));
            }
            if (view instanceof ImageView) {
                ImageView image = (ImageView) view;
                String src = tools.containsKey("src") ? tools.get("src") : tools.get("srcCompat");
                if (src != null && image.getDrawable() == null) {
                    Drawable d = drawable(src, context, env);
                    if (d != null) image.setImageDrawable(d);
                }
            }
            String background = tools.get("background");
            if (background != null && view.getBackground() == null) {
                Drawable d = drawable(background, context, env);
                if (d != null) view.setBackground(d);
            }
        } catch (RuntimeException ignored) { /* design-time hints must never break the preview */ }
    }

    /** A literal, an @string/ reference or a @tools:sample/ placeholder as text. */
    static String sample(String value, PreviewStandIns.Env env, int seed) {
        if (value == null) return null;
        if (value.startsWith("@tools:sample/")) {
            String kind = value.substring("@tools:sample/".length());
            switch (kind) {
                case "full_names": return pick(NAMES, seed);
                case "first_names": return pick(FIRST, seed);
                case "last_names": return pick(LAST, seed);
                case "cities": return pick(CITIES, seed);
                case "us_phones": case "phone_numbers": return "(555) 01" + (10 + (seed * 7) % 90) + "-0" + (100 + (seed * 13) % 900);
                case "lorem": case "lorem/random": return pick(LOREM, seed);
                default: return kind.startsWith("date") ? "12/03/2025" : pick(LOREM, seed);
            }
        }
        if (value.startsWith("@string/")) {
            int id = ProjectPreview.findIdentifier(env.resources, value.substring("@string/".length()), "string", env.packages);
            try { if (id != 0) return env.resources.getString(id); } catch (RuntimeException ignored) { }
        }
        return value;
    }

    /** @drawable/, @mipmap/, @color/, a #color or a @tools:sample/ image (a grey stand-in). */
    static Drawable drawable(String value, Context context, PreviewStandIns.Env env) {
        if (value == null) return null;
        try {
            if (value.startsWith("@tools:sample/")) {
                int size = PreviewStandIns.dp(context, 48);
                GradientDrawable shape = new GradientDrawable();
                if (value.contains("avatars")) { shape.setShape(GradientDrawable.OVAL); shape.setColor(0xFFB0BEC5); }
                else { shape.setColors(new int[] {0xFF90A4AE, 0xFFCFD8DC}); shape.setOrientation(GradientDrawable.Orientation.TL_BR); }
                shape.setSize(size, size);
                return shape;
            }
            if (value.startsWith("#")) return new ColorDrawable(Color.parseColor(value));
            int slash = value.indexOf('/');
            if (value.startsWith("@") && slash > 1) {
                String type = value.substring(1, slash), name = value.substring(slash + 1);
                int id = ProjectPreview.findIdentifier(env.resources, name, type, env.packages);
                if (id != 0) return env.resources.getDrawable(id, context.getTheme());
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static String pick(String[] values, int seed) { return values[Math.floorMod(seed, values.length)]; }
}
