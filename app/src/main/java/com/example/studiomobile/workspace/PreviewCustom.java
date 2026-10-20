package com.example.studiomobile.workspace;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The project's own views and fragments. Project code is not run in the preview, but it still says what they are:
 * a custom view is drawn as the class it extends (found in its source, Java or Kotlin, through imports and other custom views), with the layout's
 * attributes; a fragment shows the layout it inflates (tools:layout, R.layout.x, or a ViewBinding class name). Custom drawing and logic are not run.
 */
final class PreviewCustom {
    private PreviewCustom() { }

    interface Resolver { View create(String name, Context context, AttributeSet attrs); }

    private static final int MAX_DEPTH = 6;

    // ------------------------------------------------------------------ custom views

    /** A view of a class declared in the project, drawn as its base class; null when the class is not in the project or has no drawable base. */
    static View view(String name, Context context, AttributeSet attrs, List<File> sources, Resolver resolver) {
        return view(name, context, attrs, sources, resolver, 0);
    }

    private static View view(String name, Context context, AttributeSet attrs, List<File> sources, Resolver resolver, int depth) {
        if (sources == null || depth > MAX_DEPTH) return null;
        String simple = simpleName(name);
        File file = find(sources, simple);
        if (file == null) return null;
        String text = read(file);
        String base = superclass(text, simple);
        if (base == null) return null;
        String baseSimple = simpleName(base);
        if (baseSimple.equals(simple)) return null;
        if (find(sources, baseSimple) != null) return view(base, context, attrs, sources, resolver, depth + 1);   // extends another custom view
        String qualified = qualify(text, base);
        switch (baseSimple) {
            case "View": case "SurfaceView": case "TextureView": case "GLSurfaceView": return null;   // nothing to show without running the drawing code
            default: break;
        }
        View view = qualified.startsWith("android.") || qualified.indexOf('.') < 0 ? framework(qualified, context, attrs) : null;
        if (view == null && qualified.indexOf('.') >= 0) view = resolver.create(qualified, context, attrs);
        return view;
    }

    private static View framework(String base, Context context, AttributeSet attrs) {
        String[] candidates = base.indexOf('.') >= 0 ? new String[] {base}
            : new String[] {"android.widget." + base, "android.view." + base, "android.webkit." + base};
        for (String candidate : candidates) {
            try {
                return Class.forName(candidate).asSubclass(View.class).getConstructor(Context.class, AttributeSet.class).newInstance(context, attrs);
            } catch (ReflectiveOperationException | ClassCastException | LinkageError ignored) { }
        }
        return null;
    }

    /** The class after "extends" (Java) or after ":" (Kotlin) in the declaration of {@code simple}, or null. */
    static String superclass(String text, String simple) {
        String n = Pattern.quote(simple);
        Matcher m = Pattern.compile("class\\s+" + n + "\\s*(?:<[^>{]*>)?\\s+extends\\s+([\\w.]+)").matcher(text);
        if (m.find()) return m.group(1);
        m = Pattern.compile("class\\s+" + n + "\\b[\\s\\S]{0,400}?\\)\\s*:\\s*([\\w.]+)\\s*\\(").matcher(text);   // Kotlin: class X(...) : Base(...)
        if (m.find()) return m.group(1);
        m = Pattern.compile("class\\s+" + n + "\\s*:\\s*([\\w.]+)").matcher(text);                                    // Kotlin: class X : Base { constructor(...) }
        return m.find() ? m.group(1) : null;
    }

    /** The full name of {@code base} through the file's imports; unchanged when it is already qualified or not imported (same package or android.widget). */
    private static String qualify(String text, String base) {
        if (base.indexOf('.') >= 0) return base;
        Matcher m = Pattern.compile("import\\s+([\\w.]+\\." + Pattern.quote(base) + ")\\s*;?").matcher(text);
        return m.find() ? m.group(1) : base;
    }

    // ------------------------------------------------------------------ fragments

    /** &lt;fragment&gt; and FragmentContainerView: a holder with the fragment's layout inside; null when it cannot be told which layout. */
    static View fragment(Context context, AttributeSet attrs, PreviewStandIns.Env env, List<File> sources) {
        String layoutName = null;
        Map<String, String> tools = env.tools().forId(PreviewTools.idName(attrs.getIdAttributeResourceValue(View.NO_ID), env));
        String toolsLayout = tools == null ? null : tools.get("layout");
        if (toolsLayout != null) layoutName = toolsLayout.substring(toolsLayout.indexOf('/') + 1);
        if (layoutName == null && sources != null) {
            String cls = attrs.getAttributeValue(PreviewStandIns.ANDROID_NS, "name");
            if (cls == null) cls = attrs.getAttributeValue(null, "class");
            File file = cls == null ? null : find(sources, simpleName(cls));
            if (file != null) layoutName = layoutOf(read(file));
        }
        if (layoutName == null) return null;
        int id = ProjectPreview.findIdentifier(env.resources, layoutName, "layout", env.packages);
        if (id == 0) return null;
        FrameLayout holder = new FrameLayout(context, attrs);
        try {
            View content = env.items.inflate(id, holder);
            if (content == null) return null;
            PreviewTools.apply(content, env, 0);
            PreviewStandIns.sanitize(content);
            holder.addView(content);
        } catch (RuntimeException failure) { return null; }
        return holder;
    }

    /** The layout a fragment's source inflates: R.layout.x (also in the Fragment(R.layout.x) constructor) or FragmentXBinding -> fragment_x. */
    static String layoutOf(String text) {
        Matcher m = Pattern.compile("R\\.layout\\.(\\w+)").matcher(text);
        if (m.find()) return m.group(1);
        m = Pattern.compile("(\\w+)Binding\\s*\\.\\s*inflate").matcher(text);
        if (m.find()) return m.group(1).replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        return null;
    }

    // ------------------------------------------------------------------ files

    private static String simpleName(String name) { return name.substring(name.lastIndexOf('.') + 1); }

    private static File find(List<File> sources, String simple) {
        for (File file : sources) {
            String n = file.getName();
            if (n.equals(simple + ".java") || n.equals(simple + ".kt")) return file;
        }
        return null;
    }

    private static String read(File file) {
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }
}
