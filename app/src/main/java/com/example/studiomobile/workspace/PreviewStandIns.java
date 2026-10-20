package com.example.studiomobile.workspace;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;

/**
 * Preview versions of widgets that cannot be inflated from the project's resources: ConstraintLayout, RecyclerView / ListView / GridView / ViewPager
 * (with sample items from tools:listitem), TabLayout, BottomNavigationView, FloatingActionButton, TextInputLayout, Chip / ChipGroup, DrawerLayout,
 * AppBarLayout and the Material progress indicators and divider. They are built from framework views and read the app: attributes by name,
 * like {@link PreviewWidgets}. {@link #create} returns null for every other class, which then follows the normal path of the preview.
 */
final class PreviewStandIns {
    private PreviewStandIns() { }

    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    /** Inflates another layout (a list item, a header) with the same rules as the screen itself. */
    interface Items { View inflate(int layoutId, ViewGroup parent); }

    /** Marks views whose content is sample data: their children take no part in tap handling or id lookups of the screen. */
    interface Opaque { }

    static final class Env {
        final Resources resources; final String[] packages; final File resDir; final Items items;
        private PreviewTools.Index tools;
        Env(Resources resources, String[] packages, File resDir, Items items) { this.resources = resources; this.packages = packages; this.resDir = resDir; this.items = items; }
        PreviewTools.Index tools() { if (tools == null) tools = PreviewTools.Index.load(resDir); return tools; }
    }

    // ------------------------------------------------------------------ entry points

    static View create(String name, Context context, AttributeSet attrs, Env env) {
        switch (name) {
            case "androidx.constraintlayout.widget.ConstraintLayout": return new PreviewConstraint.Layout(context, attrs, env);
            case "androidx.constraintlayout.widget.Guideline": return new PreviewConstraint.Guide(context, attrs);
            case "androidx.constraintlayout.widget.Barrier":
            case "androidx.constraintlayout.widget.Group":
            case "androidx.constraintlayout.widget.Placeholder": {
                View hidden = new View(context, attrs);
                hidden.setVisibility(View.GONE);
                return hidden;
            }
            case "androidx.recyclerview.widget.RecyclerView": return new ListHost(context, attrs, env, ListHost.RECYCLER);
            case "ListView": return ListHost.listItem(attrs, env) != 0 ? new ListHost(context, attrs, env, ListHost.LIST) : null;
            case "GridView": return ListHost.listItem(attrs, env) != 0 ? new ListHost(context, attrs, env, ListHost.GRID) : null;
            case "androidx.viewpager2.widget.ViewPager2":
            case "androidx.viewpager.widget.ViewPager": return ListHost.listItem(attrs, env) != 0 ? new ListHost(context, attrs, env, ListHost.PAGER) : null;
            case "com.google.android.material.tabs.TabLayout": return new Tabs(context, attrs, env);
            case "com.google.android.material.tabs.TabItem": return new Tab(context, attrs);
            case "com.google.android.material.bottomnavigation.BottomNavigationView":
            case "com.google.android.material.navigation.NavigationBarView": return new Bar(context, attrs, env);
            case "com.google.android.material.floatingactionbutton.FloatingActionButton": return fab(context, attrs, env);
            case "com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton": return extendedFab(context, attrs, env);
            case "com.google.android.material.textfield.TextInputLayout": return new Field(context, attrs, env);
            case "com.google.android.material.chip.Chip": return chip(context, attrs, env);
            case "com.google.android.material.chip.ChipGroup": return new Flow(context, attrs, env);
            case "androidx.drawerlayout.widget.DrawerLayout": return new Drawer(context, attrs);
            case "com.google.android.material.appbar.AppBarLayout": {
                LinearLayout bar = new LinearLayout(context, attrs);
                if (attrs.getAttributeValue(ANDROID_NS, "orientation") == null) bar.setOrientation(LinearLayout.VERTICAL);   // AppBarLayout is vertical unless told otherwise
                return bar;
            }
            case "com.google.android.material.divider.MaterialDivider": {
                View divider = new View(context, attrs);
                divider.setMinimumHeight(dp(context, 1));
                if (divider.getBackground() == null) divider.setBackgroundColor(themeColor(context, env, "colorOutlineVariant", 0x33808080));
                return divider;
            }
            case "com.google.android.material.progressindicator.LinearProgressIndicator": return progress(context, attrs, env, true);
            case "com.google.android.material.progressindicator.CircularProgressIndicator": return progress(context, attrs, env, false);
            default: return null;
        }
    }

    /** After inflating: widgets that came out empty (tabs, bottom bar items) get sample content so they are not blank. */
    static void finish(View view) {
        if (view instanceof Tabs) ((Tabs) view).fillIfEmpty();
        if (view instanceof Bar) ((Bar) view).fillIfEmpty();
        if (view instanceof ViewGroup && !(view instanceof Opaque) && !(view instanceof WebView)) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) finish(group.getChildAt(i));
        }
    }

    // ------------------------------------------------------------------ helpers

    static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    /** A color of the project's theme by attribute name (colorPrimary, colorSurface, ...), else the fallback. */
    static int themeColor(Context context, Env env, String attrName, int fallback) {
        try {
            int id = ProjectPreview.findIdentifier(env.resources, attrName, "attr", env.packages);
            TypedValue value = new TypedValue();
            if (id != 0 && context.getTheme().resolveAttribute(id, value, true)) {
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
                if (value.resourceId != 0) return context.getResources().getColor(value.resourceId, context.getTheme());
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    private static boolean isMaterial3(Context context, Env env) { return themeColor(context, env, "colorPrimaryContainer", Integer.MIN_VALUE) != Integer.MIN_VALUE; }

    private static int intValue(Map<String, String> tools, String key, int fallback) {
        try { return tools == null || tools.get(key) == null ? fallback : Integer.parseInt(tools.get(key).trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static Map<String, String> toolsFor(AttributeSet attrs, Env env) {
        return env.tools().forId(PreviewTools.idName(attrs.getIdAttributeResourceValue(View.NO_ID), env));
    }

    /** Sample content must not react to taps with android:onClick (the method does not exist here) nor shadow ids of the screen. */
    static void sanitize(View view) {
        if (view.hasOnClickListeners()) view.setOnClickListener(x -> { });
        view.setId(View.NO_ID);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) sanitize(group.getChildAt(i));
        }
    }

    private static Drawable dot(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.WHITE);
        shape.setSize(dp(context, 24), dp(context, 24));
        return shape;
    }

    // ------------------------------------------------------------------ lists

    /** RecyclerView, ListView, GridView or ViewPager: the item layout from tools:listitem repeated, or grey placeholder rows when the items come from code. */
    static final class ListHost extends FrameLayout implements Opaque {
        static final int RECYCLER = 0, LIST = 1, GRID = 2, PAGER = 3;

        static int listItem(AttributeSet attrs, Env env) {
            Map<String, String> tools = toolsFor(attrs, env);
            String ref = tools == null ? null : tools.get("listitem");
            if (ref == null) return 0;
            int slash = ref.indexOf('/');
            return ProjectPreview.findIdentifier(env.resources, slash >= 0 ? ref.substring(slash + 1) : ref, "layout", env.packages);
        }

        ListHost(Context context, AttributeSet attrs, Env env, int kind) {
            super(context, attrs);
            Map<String, String> tools = toolsFor(attrs, env);
            int item = listItem(attrs, env);
            int count = kind == PAGER ? 1 : Math.max(1, Math.min(12, intValue(tools, "itemCount", kind == GRID ? 8 : 6)));
            int orientation = 1, span = 1;
            if (kind == RECYCLER) {
                TypedArray o = context.obtainStyledAttributes(attrs, new int[] {android.R.attr.orientation});
                try { orientation = o.getInt(0, 1); } finally { o.recycle(); }
                PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
                String manager = r.string("layoutManager");
                boolean grid = manager != null && (manager.contains("Grid") || manager.contains("Staggered"));
                span = Math.max(grid ? 2 : 1, r.integer("spanCount", 1));
            } else if (kind == GRID) {
                TypedArray g = context.obtainStyledAttributes(attrs, new int[] {android.R.attr.numColumns});
                try { span = g.getInt(0, 2); } finally { g.recycle(); }
                if (span < 1) span = 2;   // auto_fit
            }
            if (kind == PAGER) {
                View page = null;
                try { page = env.items.inflate(item, this); } catch (RuntimeException ignored) { }
                if (page != null) {
                    PreviewTools.apply(page, env, 0);
                    sanitize(page);
                    addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));
                }
                return;
            }
            boolean horizontal = orientation == 0 && span == 1;
            LinearLayout list = new LinearLayout(context);
            list.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            if (item == 0 || !fill(context, env, list, item, count, span)) {
                list.removeAllViews();
                skeleton(context, list, horizontal ? 3 : 5, kind == RECYCLER ? "RecyclerView · its items are created in code" : "List · its items are created in code");
            }
            ViewGroup scroll = horizontal ? new HorizontalScrollView(context) : new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(list, horizontal ? new FrameLayout.LayoutParams(WRAP, MATCH) : new FrameLayout.LayoutParams(MATCH, WRAP));
            addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        }

        private static boolean fill(Context context, Env env, LinearLayout list, int item, int count, int span) {
            int made = 0;
            try {
                while (made < count) {
                    ViewGroup row = list;
                    if (span > 1) {
                        LinearLayout line = new LinearLayout(context);
                        line.setOrientation(LinearLayout.HORIZONTAL);
                        list.addView(line, new LinearLayout.LayoutParams(MATCH, WRAP));
                        row = line;
                    }
                    for (int k = 0; k < Math.max(1, span) && made < count; k++, made++) {
                        View view = env.items.inflate(item, row);
                        if (view == null) return false;
                        PreviewTools.apply(view, env, made);
                        sanitize(view);
                        if (span > 1) row.addView(view, new LinearLayout.LayoutParams(0, WRAP, 1f)); else row.addView(view);
                    }
                }
                return true;
            } catch (RuntimeException failure) { return false; }
        }

        private static void skeleton(Context c, LinearLayout list, int rows, String caption) {
            int gray = 0x1F808080;
            TextView note = new TextView(c);
            note.setText(caption); note.setTextSize(11); note.setTextColor(0x99808080);
            note.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 4));
            list.addView(note, new LinearLayout.LayoutParams(WRAP, WRAP));
            for (int i = 0; i < rows; i++) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
                View avatar = new View(c);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL); circle.setColor(gray);
                avatar.setBackground(circle);
                row.addView(avatar, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
                LinearLayout lines = new LinearLayout(c);
                lines.setOrientation(LinearLayout.VERTICAL);
                lines.setPadding(dp(c, 12), 0, 0, 0);
                lines.addView(bar(c, 150 - (i % 3) * 20, 10, gray), new LinearLayout.LayoutParams(dp(c, 150 - (i % 3) * 20), dp(c, 10)));
                LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(dp(c, 200), dp(c, 8));
                second.topMargin = dp(c, 8);
                lines.addView(bar(c, 200, 8, gray), second);
                row.addView(lines, new LinearLayout.LayoutParams(WRAP, WRAP));
                list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
        }

        private static View bar(Context c, int widthDp, int heightDp, int color) {
            View bar = new View(c);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(c, 4)); shape.setColor(color);
            bar.setBackground(shape);
            return bar;
        }
    }

    // ------------------------------------------------------------------ tabs

    static final class Tabs extends LinearLayout {
        private final boolean scrollable;
        private final int indicator, selectedText, normalText;

        Tabs(Context context, AttributeSet attrs, Env env) {
            super(context, attrs);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
            scrollable = r.integer("tabMode", 1) == 0;
            ColorStateList text = r.color("tabTextColor"), selected = r.color("tabSelectedTextColor"), line = r.color("tabIndicatorColor");
            int primary = themeColor(context, env, "colorPrimary", 0xFF6750A4), onSurface = themeColor(context, env, "colorOnSurface", 0xFF1C1B1F);
            int[] selectedState = {android.R.attr.state_selected};
            indicator = line != null ? line.getDefaultColor() : primary;
            selectedText = selected != null ? selected.getDefaultColor() : text != null ? text.getColorForState(selectedState, text.getDefaultColor()) : primary;
            normalText = text != null ? text.getDefaultColor() : ColorUtils.setAlphaComponent(onSurface, 0x99);
        }

        @Override public void onViewAdded(View child) {
            super.onViewAdded(child);
            if (!(child instanceof Tab)) return;
            ((Tab) child).style(indicator, selectedText, normalText, indexOfChild(child) == 0);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (!scrollable && params instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
                lp.width = 0; lp.weight = 1f;
            }
        }

        void fillIfEmpty() {
            if (getChildCount() > 0) return;
            String[] titles = {"Tab 1", "Tab 2", "Tab 3"};
            for (String title : titles) {
                Tab tab = new Tab(getContext(), null);
                tab.setText(title);
                addView(tab, new LinearLayout.LayoutParams(scrollable ? WRAP : 0, WRAP, scrollable ? 0f : 1f));
            }
        }
    }

    static final class Tab extends TextView {
        private boolean selected;
        private int indicator;
        private final Paint paint = new Paint();

        Tab(Context context, AttributeSet attrs) {
            super(context, attrs);
            setGravity(Gravity.CENTER);
            setTextSize(14);
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
            if (attrs != null) {
                TypedArray a = context.obtainStyledAttributes(attrs, new int[] {android.R.attr.icon});
                try {
                    Drawable icon = a.getDrawable(0);
                    if (icon != null) { setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null); setCompoundDrawablePadding(dp(context, 4)); }
                } finally { a.recycle(); }
            }
        }

        void style(int indicatorColor, int selectedColor, int normalColor, boolean isSelected) {
            indicator = indicatorColor; selected = isSelected;
            setTextColor(isSelected ? selectedColor : normalColor);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!selected) return;
            paint.setColor(indicator);
            canvas.drawRect(0, getHeight() - dp(getContext(), 3), getWidth(), getHeight(), paint);
        }
    }

    // ------------------------------------------------------------------ bottom navigation

    private static final class MenuEntry { String title; Drawable icon; }

    /** The items of a menu resource (top level only, at most {@code limit}). */
    private static List<MenuEntry> menu(Context context, Env env, int menuId, int limit) {
        List<MenuEntry> out = new ArrayList<>();
        if (menuId <= 0) return out;
        XmlResourceParser parser = null;
        try {
            parser = env.resources.getXml(menuId);
            int depth = 0;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT && out.size() < limit; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if (tag.equals("menu")) depth++;
                    else if (tag.equals("item") && depth == 1) {
                        MenuEntry entry = new MenuEntry();
                        int titleRes = parser.getAttributeResourceValue(ANDROID_NS, "title", 0);
                        entry.title = titleRes != 0 ? env.resources.getString(titleRes) : parser.getAttributeValue(ANDROID_NS, "title");
                        int iconRes = parser.getAttributeResourceValue(ANDROID_NS, "icon", 0);
                        try { if (iconRes != 0) entry.icon = env.resources.getDrawable(iconRes, context.getTheme()); } catch (RuntimeException ignored) { }
                        if (entry.title == null) entry.title = "Item";
                        out.add(entry);
                    }
                } else if (event == XmlPullParser.END_TAG && parser.getName().equals("menu")) depth--;
            }
        } catch (Exception ignored) {
            /* a menu that cannot be read gives the sample items */
        } finally {
            if (parser != null) parser.close();
        }
        return out;
    }

    static final class Bar extends LinearLayout {
        private final int iconSelected, iconNormal, textSelected, textNormal;

        Bar(Context context, AttributeSet attrs, Env env) {
            super(context, attrs);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(context, 64));
            PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
            ColorStateList iconTint = r.color("itemIconTint"), textTint = r.color("itemTextColor");
            int primary = themeColor(context, env, "colorPrimary", 0xFF6750A4), onSurface = themeColor(context, env, "colorOnSurface", 0xFF1C1B1F);
            int[] checked = {android.R.attr.state_checked};
            int muted = ColorUtils.setAlphaComponent(onSurface, 0x99);
            iconSelected = iconTint != null ? iconTint.getColorForState(checked, iconTint.getDefaultColor()) : primary;
            iconNormal = iconTint != null ? iconTint.getDefaultColor() : muted;
            textSelected = textTint != null ? textTint.getColorForState(checked, textTint.getDefaultColor()) : iconSelected;
            textNormal = textTint != null ? textTint.getDefaultColor() : muted;
            if (getBackground() == null) {
                setBackgroundColor(themeColor(context, env, "colorSurface", 0xFFFFFBFE));
                setElevation(dp(context, 8));
            }
            List<MenuEntry> entries = menu(context, env, r.reference("menu", -1, -1), 5);
            for (int i = 0; i < entries.size(); i++) addItem(entries.get(i).title, entries.get(i).icon, i == 0);
        }

        void fillIfEmpty() {
            if (getChildCount() > 0) return;
            addItem("Home", null, true); addItem("Explore", null, false); addItem("Profile", null, false);
        }

        private void addItem(String title, Drawable icon, boolean selected) {
            Context c = getContext();
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(c, 8), 0, dp(c, 8));
            ImageView image = new ImageView(c);
            Drawable drawable = icon != null ? icon.mutate() : dot(c);
            drawable.setTint(selected ? iconSelected : iconNormal);
            image.setImageDrawable(drawable);
            image.setPadding(dp(c, 20), dp(c, 4), dp(c, 20), dp(c, 4));
            if (selected) {
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(c, 16));
                pill.setColor(ColorUtils.setAlphaComponent(iconSelected, 0x33));
                image.setBackground(pill);
            }
            item.addView(image, new LinearLayout.LayoutParams(dp(c, 64), dp(c, 32)));
            TextView label = new TextView(c);
            label.setText(title); label.setTextSize(12); label.setSingleLine(true);
            label.setTextColor(selected ? textSelected : textNormal);
            item.addView(label, new LinearLayout.LayoutParams(WRAP, WRAP));
            addView(item, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
    }

    // ------------------------------------------------------------------ buttons

    private static View fab(Context context, AttributeSet attrs, Env env) {
        ImageView view = new ImageView(context, attrs);
        PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
        boolean m3 = isMaterial3(context, env);
        int fill = themeColor(context, env, m3 ? "colorPrimaryContainer" : "colorSecondary", themeColor(context, env, "colorPrimary", 0xFF6750A4));
        int onFill = themeColor(context, env, m3 ? "colorOnPrimaryContainer" : "colorOnSecondary", Color.WHITE);
        float custom = r.dimension("fabCustomSize", 0f);
        boolean mini = r.integer("fabSize", -1) == 1;
        int size = custom > 0f ? Math.round(custom) : dp(context, mini ? 40 : 56);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(m3 ? dp(context, mini ? 12 : 16) : size / 2f);
        view.setBackground(shape);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Math.max(0, (size - dp(context, 24)) / 2);
        view.setPadding(pad, pad, pad, pad);
        view.setMinimumWidth(size);
        view.setMinimumHeight(size);
        view.setImageTintList(ColorStateList.valueOf(onFill));
        view.setElevation(dp(context, 6));
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        PreviewWidgets.style(view, "FloatingActionButton", attrs, context, env.resources, env.packages);   // srcCompat, tint, backgroundTint
        return view;
    }

    private static View extendedFab(Context context, AttributeSet attrs, Env env) {
        Button button = new Button(context, attrs);
        PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
        boolean m3 = isMaterial3(context, env);
        int fill = themeColor(context, env, m3 ? "colorPrimaryContainer" : "colorSecondary", themeColor(context, env, "colorPrimary", 0xFF6750A4));
        int onFill = themeColor(context, env, m3 ? "colorOnPrimaryContainer" : "colorOnSecondary", Color.WHITE);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, m3 ? 16 : 28));
        button.setBackground(shape);
        button.setStateListAnimator(null);
        button.setAllCaps(false);
        button.setTextColor(onFill);
        button.setMinHeight(dp(context, 56));
        button.setMinimumHeight(dp(context, 56));
        button.setPadding(dp(context, 16), 0, dp(context, 20), 0);
        button.setElevation(dp(context, 6));
        Drawable icon = r.drawable("icon");
        if (icon != null) {
            Drawable d = icon.mutate();
            d.setTint(onFill);
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
            button.setCompoundDrawablePadding(dp(context, 8));
        }
        PreviewWidgets.style(button, "ExtendedFloatingActionButton", attrs, context, env.resources, env.packages);
        return button;
    }

    private static View chip(Context context, AttributeSet attrs, Env env) {
        TextView chip = new TextView(context, attrs);
        PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
        ColorStateList fill = r.color("chipBackgroundColor"), stroke = r.color("chipStrokeColor");
        float radius = r.dimension("chipCornerRadius", dp(context, 8)), strokeWidth = r.dimension("chipStrokeWidth", dp(context, 1));
        int onSurface = themeColor(context, env, "colorOnSurface", 0xFF1C1B1F);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(radius);
        shape.setColor(fill != null ? fill : ColorStateList.valueOf(Color.TRANSPARENT));
        shape.setStroke(Math.max(0, Math.round(strokeWidth)), stroke != null ? stroke : ColorStateList.valueOf(themeColor(context, env, "colorOutline", 0xFF79747E)));
        chip.setBackground(shape);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinHeight(dp(context, 32));
        chip.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        if (attrs.getAttributeValue(ANDROID_NS, "textSize") == null) chip.setTextSize(14);
        if (attrs.getAttributeValue(ANDROID_NS, "textColor") == null) chip.setTextColor(onSurface);
        return chip;
    }

    private static View progress(Context context, AttributeSet attrs, Env env, boolean linear) {
        ProgressBar bar = new ProgressBar(context, attrs, 0, linear ? android.R.style.Widget_ProgressBar_Horizontal : android.R.style.Widget_ProgressBar);
        int primary = themeColor(context, env, "colorPrimary", 0xFF6750A4);
        ColorStateList tint = ColorStateList.valueOf(primary);
        bar.setIndeterminateTintList(tint);
        bar.setProgressTintList(tint);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 0x33)));
        return bar;
    }

    // ------------------------------------------------------------------ text field

    /** TextInputLayout: an outlined (or filled) box with the hint as a small label above the EditText it wraps. */
    static final class Field extends LinearLayout {
        private final LinearLayout box;
        private boolean building = true;

        Field(Context context, AttributeSet attrs, Env env) {
            super(context, attrs);
            setOrientation(VERTICAL);
            PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
            String hint, helper = r.string("helperText");
            TypedArray a = context.obtainStyledAttributes(attrs, new int[] {android.R.attr.hint});
            try { hint = a.getString(0); } finally { a.recycle(); }
            int mode = r.integer("boxBackgroundMode", 2);   // 0 none, 1 filled, 2 outline
            int onSurface = themeColor(context, env, "colorOnSurface", 0xFF1C1B1F);
            box = new LinearLayout(context);
            box.setOrientation(VERTICAL);
            box.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
            if (mode != 0) {
                GradientDrawable shape = new GradientDrawable();
                float corner = dp(context, 4);
                if (mode == 2) {
                    shape.setCornerRadius(corner);
                    shape.setStroke(dp(context, 1), themeColor(context, env, "colorOutline", 0xFF79747E));
                } else {
                    shape.setCornerRadii(new float[] {corner, corner, corner, corner, 0, 0, 0, 0});
                    shape.setColor(ColorUtils.setAlphaComponent(onSurface, 0x0D));
                }
                box.setBackground(shape);
            }
            if (hint != null && hint.length() > 0) {
                TextView label = new TextView(context);
                label.setText(hint); label.setTextSize(12); label.setSingleLine(true);
                label.setTextColor(ColorUtils.setAlphaComponent(themeColor(context, env, "colorPrimary", 0xFF6750A4), 0xDD));
                box.addView(label, new LinearLayout.LayoutParams(WRAP, WRAP));
            }
            super.addView(box, new LinearLayout.LayoutParams(MATCH, WRAP));
            if (helper != null) {
                TextView note = new TextView(context);
                note.setText(helper); note.setTextSize(12);
                note.setTextColor(ColorUtils.setAlphaComponent(onSurface, 0x99));
                note.setPadding(dp(context, 16), dp(context, 4), dp(context, 16), 0);
                super.addView(note, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            building = false;
        }

        /** The wrapped EditText goes inside the box, without its own underline. */
        @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
            if (building) { super.addView(child, index, params); return; }
            if (child instanceof EditText) {
                child.setBackground(null);
                child.setPadding(0, dp(getContext(), 2), 0, dp(getContext(), 2));
            }
            box.addView(child, params);
        }
    }

    // ------------------------------------------------------------------ chips, drawer

    /** ChipGroup: children wrap onto new lines. */
    static final class Flow extends ViewGroup {
        private final int hGap, vGap;
        private int[] px = new int[0], py = new int[0];

        Flow(Context context, AttributeSet attrs, Env env) {
            super(context, attrs);
            PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
            float common = r.dimension("chipSpacing", dp(context, 8));
            hGap = Math.round(r.dimension("chipSpacingHorizontal", common));
            vGap = Math.round(r.dimension("chipSpacingVertical", common));
        }

        @Override public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) { return new ViewGroup.MarginLayoutParams(getContext(), attrs); }
        @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() { return new ViewGroup.MarginLayoutParams(WRAP, WRAP); }
        @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) { return new ViewGroup.MarginLayoutParams(p); }
        @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof ViewGroup.MarginLayoutParams; }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int n = getChildCount();
            px = new int[n]; py = new int[n];
            int maxWidth = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowHeight = 0, usedWidth = 0;
            for (int i = 0; i < n; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                measureChildWithMargins(child, widthSpec, 0, heightSpec, 0);
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                int w = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin, h = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (x > 0 && x + w > maxWidth) { x = 0; y += rowHeight + vGap; rowHeight = 0; }
                px[i] = x + lp.leftMargin; py[i] = y + lp.topMargin;
                x += w + hGap;
                rowHeight = Math.max(rowHeight, h);
                usedWidth = Math.max(usedWidth, x - hGap);
            }
            setMeasuredDimension(resolveSize(usedWidth + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(y + rowHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount() && i < px.length; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int left = getPaddingLeft() + px[i], top = getPaddingTop() + py[i];
                child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
            }
        }
    }

    /** DrawerLayout: the content is shown and the side drawers stay closed, which is how the screen starts. */
    static final class Drawer extends FrameLayout {
        Drawer(Context context, AttributeSet attrs) { super(context, attrs); }

        @Override public void onViewAdded(View child) {
            super.onViewAdded(child);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (!(params instanceof FrameLayout.LayoutParams)) return;
            int gravity = Gravity.getAbsoluteGravity(((FrameLayout.LayoutParams) params).gravity, View.LAYOUT_DIRECTION_LTR) & Gravity.HORIZONTAL_GRAVITY_MASK;
            if (gravity == Gravity.LEFT || gravity == Gravity.RIGHT) child.setVisibility(GONE);
        }
    }
}
