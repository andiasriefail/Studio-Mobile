package com.example.studiomobile.workspace;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws the preview: a phone design whose status bar, navigation bar and title bar follow the project (see {@link PreviewBars}),
 * with the project's layout inflated from its own resources and/or its assets page loaded in a real WebView.
 * The phone keeps a stack of screens: a tap that starts another Activity in the project's code opens that screen, and the navigation bar's
 * Back button (or the system Back) returns to the previous one, so the preview can be used even when the app has no back button of its own.
 * Must run on the UI thread.
 */
public final class PreviewRenderer {
    private PreviewRenderer() { }

    public interface TipListener { void onTip(String text); }

    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final int BEZEL = 10, CORNER = 38, MAX_SCREENS = 24;

    // ------------------------------------------------------------------ entry points

    public static PhoneView render(Activity activity, ProjectPreview.Prepared p, TipListener tips) {
        PhoneView phone = new PhoneView(activity, tips);
        phone.push(p);
        return phone;
    }

    /** Stops and frees every WebView under {@code root}, including those of screens that are not currently shown. Call before the preview screen is removed. */
    public static void release(View root) {
        List<WebView> found = new ArrayList<>();
        collectWebViews(root, found);
        for (WebView web : found) destroy(web);
        PreviewJsx.release();
    }

    private static void destroy(WebView web) {
        try {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.stopLoading(); web.destroy();
        } catch (RuntimeException ignored) { }
    }

    private static void collectWebViews(View view, List<WebView> out) {
        if (view == null) return;
        if (view instanceof WebView) { out.add((WebView) view); return; }
        if (view instanceof PhoneView) { for (View screen : ((PhoneView) view).allScreens()) collectWebViews(screen, out); return; }   // every screen of the stack, shown or not
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) collectWebViews(group.getChildAt(i), out); }
    }

    // ------------------------------------------------------------------ device facts (nothing here is a guess about "a phone")

    /** Real screen size in pixels, portrait (short side first), the way the build would see it on this device. */
    private static int[] deviceSizePx(Activity activity) {
        int w, h;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
            w = bounds.width(); h = bounds.height();
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            w = metrics.widthPixels; h = metrics.heightPixels;
        }
        return new int[] {Math.min(w, h), Math.max(w, h)};
    }

    private static int systemDimension(String name, int fallback) {
        try {
            int id = Resources.getSystem().getIdentifier(name, "dimen", "android");
            if (id != 0) { int value = Resources.getSystem().getDimensionPixelSize(id); if (value > 0) return value; }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    /** True for the three-button navigation bar, false for gesture navigation; three buttons when the system does not say. */
    private static boolean threeButtonNavigation(Context context) {
        try { return Settings.Secure.getInt(context.getContentResolver(), "navigation_mode", 0) != 2; }
        catch (RuntimeException unknown) { return true; }
    }

    private static int actionBarHeight(Context context, ProjectPreview.Prepared p) {
        float d = context.getResources().getDisplayMetrics().density;
        TypedValue value = new TypedValue();
        try {
            if (p.theme != null && p.theme.resolveAttribute(android.R.attr.actionBarSize, value, true) && value.type == TypedValue.TYPE_DIMENSION)
                return Math.round(value.getDimension(context.getResources().getDisplayMetrics()));
        } catch (RuntimeException ignored) { }
        return systemDimension("action_bar_default_height_material", Math.round(56 * d));
    }

    // ------------------------------------------------------------------ content

    private static void fillContent(Activity activity, ProjectPreview.Prepared p, FrameLayout content, FrameLayout overlay, TipListener tips, PhoneView phone) {
        if (p.mode == ProjectPreview.Mode.WEB) {
            WebView web = new WebView(activity);
            configureWebView(activity, web, p, overlay, tips);
            content.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        try {
            PreviewContext previewContext = new PreviewContext(activity, p.resources, p.theme);
            LayoutInflater inflater = LayoutInflater.from(previewContext);
            PreviewFactory factory = new PreviewFactory(activity, p, overlay, tips);
            factory.previewContext = previewContext;
            inflater.setFactory2(factory);
            View inflated = inflater.inflate(p.layoutId, content, false);
            PreviewTools.apply(inflated, factory.env, 0);   // tools:text, tools:src, ... of the layout source
            PreviewStandIns.finish(inflated);
            wireClicks(inflated, p, phone);
            applyEffects(inflated, p, p.startEffects, false);
            content.addView(inflated);
            String unsupported = factory.unsupportedSummary();
            if (unsupported != null) tips.onTip(unsupported);
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            TextView error = new TextView(activity);
            error.setPadding(40, 40, 40, 40);
            error.setTextColor(0xFFB3261E);
            error.setTextSize(13);
            error.setText("This layout could not be shown.\n\n" + root.getClass().getSimpleName() + ": " + root.getMessage());
            content.addView(error);
        }
    }

    /**
     * android:onClick would look for a method in the host Activity and crash when it is missing; project code is not run in the preview.
     * Instead, the taps the code is known to handle (see {@link PreviewLinks}) are connected: open another screen, show a Toast, finish.
     */
    private static void wireClicks(View root, ProjectPreview.Prepared p, PhoneView phone) {
        List<View> declared = new ArrayList<>();
        collectClickable(root, declared);
        boolean aligned = declared.size() == p.onClickActions.size();
        for (int i = 0; i < declared.size(); i++) {
            PreviewLinks.Action action = aligned ? p.onClickActions.get(i) : null;
            bind(declared.get(i), action, p, phone, root);
        }
        for (Map.Entry<String, PreviewLinks.Action> link : p.links.entrySet()) {
            int id = ProjectPreview.findIdentifier(p.resources, link.getKey(), "id", p.packages);
            View target = id == 0 ? null : root.findViewById(id);
            if (target != null) bind(target, link.getValue(), p, phone, root);
        }
    }

    private static void bind(View view, PreviewLinks.Action action, ProjectPreview.Prepared p, PhoneView phone, View root) {
        if (action == null) view.setOnClickListener(v -> { });
        else view.setOnClickListener(v -> phone.perform(action, p, root));
    }

    /**
     * Applies what the code does to views (visibility, text, colors, enabled, ...). A click animates the change like the platform's
     * default transition; the first frame is set without animation. A "toggle" flips the view's current visibility.
     */
    static void applyEffects(View root, ProjectPreview.Prepared p, List<PreviewLinks.Effect> effects, boolean animate) {
        if (effects == null || effects.isEmpty()) return;
        if (animate && root instanceof ViewGroup) {
            try { android.transition.TransitionManager.beginDelayedTransition((ViewGroup) root); } catch (RuntimeException ignored) { }
        }
        for (PreviewLinks.Effect effect : effects) {
            int id = ProjectPreview.findIdentifier(p.resources, effect.target, "id", p.packages);
            View view = id == 0 ? null : root.findViewById(id);
            if (view == null) continue;
            try {
                switch (effect.kind) {
                    case "visibility":
                        if (effect.value.equals("toggle")) view.setVisibility(view.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                        else view.setVisibility(effect.value.equals("gone") ? View.GONE : effect.value.equals("invisible") ? View.INVISIBLE : View.VISIBLE);
                        break;
                    case "text": if (view instanceof TextView) ((TextView) view).setText(resolveText(effect.value, p)); break;
                    case "textColor": if (view instanceof TextView) ((TextView) view).setTextColor(resolveColor(effect.value, p)); break;
                    case "background": view.setBackgroundColor(resolveColor(effect.value, p)); break;
                    case "enabled": view.setEnabled(Boolean.parseBoolean(effect.value)); break;
                    case "clickable": view.setClickable(Boolean.parseBoolean(effect.value)); break;
                    case "checked": if (view instanceof android.widget.Checkable) ((android.widget.Checkable) view).setChecked(Boolean.parseBoolean(effect.value)); break;
                    case "alpha": view.setAlpha(Float.parseFloat(effect.value)); break;
                    default: break;
                }
            } catch (RuntimeException ignored) { }
        }
    }

    private static String resolveText(String value, ProjectPreview.Prepared p) {
        if (!value.startsWith("@string/")) return value;
        int id = ProjectPreview.findIdentifier(p.resources, value.substring("@string/".length()), "string", p.packages);
        try { if (id != 0) return p.resources.getString(id); } catch (RuntimeException ignored) { }
        return value;
    }

    private static int resolveColor(String value, ProjectPreview.Prepared p) {
        if (value.startsWith("@color/")) {
            int id = ProjectPreview.findIdentifier(p.resources, value.substring("@color/".length()), "color", p.packages);
            if (id != 0) return p.resources.getColor(id, p.theme);
            return Color.GRAY;
        }
        return Color.parseColor(value);
    }

    private static void collectClickable(View view, List<View> out) {
        if (view == null || view instanceof WebView || view instanceof PreviewStandIns.Opaque) return;
        if (view.hasOnClickListeners()) out.add(view);
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) collectClickable(group.getChildAt(i), out); }
    }

    private static Drawable windowBackground(ProjectPreview.Prepared p) {
        try {
            TypedValue value = new TypedValue();
            if (p.theme != null && p.theme.resolveAttribute(android.R.attr.windowBackground, value, true)) {
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return new ColorDrawable(value.data);
                if (value.resourceId != 0) return p.resources.getDrawable(value.resourceId, p.theme);
            }
        } catch (RuntimeException ignored) { }
        return new ColorDrawable(Color.WHITE);
    }

    private static void dispatchInsets(FrameLayout content, int top, int bottom) {
        content.post(() -> {
            try {
                WindowInsets platform = new WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, top, 0, bottom)).build().toWindowInsets();
                if (platform != null) content.dispatchApplyWindowInsets(platform);
            } catch (Throwable ignored) { }
        });
    }

    // ------------------------------------------------------------------ bars

    private static View statusBar(Context context, PreviewBars bars, int background, TipListener tips, View shade, boolean vpn) {
        StatusBarView view = new StatusBarView(context, bars.lightStatus ? 0xDD000000 : 0xFFFFFFFF, vpn);
        view.setBackgroundColor(background);
        if (shade == null) view.setOnClickListener(v -> tips.onTip(bars.statusInfo));
        else PreviewShade.attachOpener(view, shade);   // tap or swipe: the project's notification shade (tiles, media notification)
        return view;
    }

    private static View navBar(Context context, PreviewBars bars, int background, boolean threeButton, Runnable onBack, TipListener tips, Runnable onHome) {
        NavBarView view = new NavBarView(context, bars.lightNav ? 0xCC000000 : 0xCCFFFFFF, threeButton, onBack, () -> { if (onHome != null) onHome.run(); else tips.onTip(bars.navInfo); });
        view.setBackgroundColor(background);
        return view;
    }

    private static View actionBar(Context context, PreviewBars bars, int topPadding, TipListener tips, Runnable onUp) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(bars.actionBarColor);
        float d = context.getResources().getDisplayMetrics().density;
        bar.setPadding(Math.round(16 * d), topPadding, Math.round(16 * d), 0);
        int ink = ColorUtils.calculateLuminance(bars.actionBarColor) > 0.5 ? 0xDD000000 : Color.WHITE;
        if (bars.homeAsUp) {
            UpArrowView up = new UpArrowView(context, ink);
            up.setOnClickListener(v -> onUp.run());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Math.round(32 * d), Math.round(32 * d));
            params.rightMargin = Math.round(16 * d);
            bar.addView(up, params);
        }
        TextView title = new TextView(context);
        title.setSingleLine(true);
        title.setText(bars.title);
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setTextColor(ink);
        bar.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setOnClickListener(v -> tips.onTip(bars.actionBarInfo));
        return bar;
    }

    /** The "up" arrow of an action bar. */
    private static final class UpArrowView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG); private final float d;
        UpArrowView(Context context, int color) {
            super(context);
            d = context.getResources().getDisplayMetrics().density;
            line.setColor(color); line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND); line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeWidth(2 * d);
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = 8 * d;
            canvas.drawLine(w / 2 - r, h / 2, w / 2 + r, h / 2, line);
            Path head = new Path();
            head.moveTo(w / 2 - r + 6 * d, h / 2 - 6 * d); head.lineTo(w / 2 - r, h / 2); head.lineTo(w / 2 - r + 6 * d, h / 2 + 6 * d);
            canvas.drawPath(head, line);
        }
    }

    /** Status bar: real clock, signal, wifi and battery drawn in the icon color the project asks for. */
    private static final class StatusBarView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d; private final float battery; private final boolean vpn;

        StatusBarView(Context context, int iconColor, boolean vpn) {
            super(context);
            this.vpn = vpn;
            d = context.getResources().getDisplayMetrics().density;
            text.setColor(iconColor); text.setTextSize(13 * d); text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            line.setColor(iconColor); line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND);
            fill.setColor(iconColor); fill.setStyle(Paint.Style.FILL);
            float level = 0.8f;
            try {
                Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (status != null) { int l = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1); if (l >= 0 && s > 0) level = l / (float) s; }
            } catch (RuntimeException ignored) { }
            battery = level;
        }

        @Override protected void onDraw(Canvas canvas) {
            float width = getWidth(), cy = getHeight() / 2f;
            String time = new java.text.SimpleDateFormat(DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date());
            canvas.drawText(time, 18 * d, cy - (text.ascent() + text.descent()) / 2f, text);

            float x = width - 16 * d;
            // battery
            line.setStrokeWidth(1.2f * d);
            float bw = 22 * d, bh = 11 * d, right = x - 2 * d;
            RectF body = new RectF(right - bw, cy - bh / 2, right, cy + bh / 2);
            canvas.drawRoundRect(body, 3 * d, 3 * d, line);
            canvas.drawRoundRect(new RectF(right, cy - 2.5f * d, right + 2 * d, cy + 2.5f * d), 1 * d, 1 * d, fill);
            canvas.drawRoundRect(new RectF(body.left + 2 * d, body.top + 2 * d, body.left + 2 * d + (bw - 4 * d) * battery, body.bottom - 2 * d), 1.5f * d, 1.5f * d, fill);
            x = body.left - 8 * d;
            // wifi
            line.setStrokeWidth(1.8f * d);
            float cx = x - 9 * d, by = cy + 6 * d;
            for (float r : new float[] {5 * d, 9 * d, 13 * d}) canvas.drawArc(new RectF(cx - r, by - r, cx + r, by + r), 225, 90, false, line);
            canvas.drawCircle(cx, by - 0.5f * d, 1.6f * d, fill);
            x = cx - 13 * 0.7071f * d - 8 * d;
            // signal
            float left = x - 18 * d, baseline = cy + 6.5f * d;
            float[] heights = {4 * d, 7 * d, 10 * d, 13 * d};
            for (int i = 0; i < 4; i++) canvas.drawRoundRect(new RectF(left + i * 5 * d, baseline - heights[i], left + i * 5 * d + 3 * d, baseline), 1 * d, 1 * d, fill);
            // VPN key, left of the signal bars, when the project has a VpnService
            if (vpn) { line.setStrokeWidth(1.5f * d); PreviewShade.drawKey(canvas, line, left - 6 * d - 14 * d, cy, 14 * d); }
            if (isAttachedToWindow()) postInvalidateDelayed(20000);
        }
    }


    /**
     * Navigation bar. Three-button mode (as on most phones that have it) draws Back, Home and Recents and Back really goes back in the preview;
     * gesture mode draws the thin pill with a Back chevron at the left that does the same. Home and Recents explain themselves in a tip.
     */
    private static final class NavBarView extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d; private final boolean threeButton; private final Runnable onBack, onOther;

        NavBarView(Context context, int color, boolean threeButton, Runnable onBack, Runnable onOther) {
            super(context);
            d = context.getResources().getDisplayMetrics().density;
            this.threeButton = threeButton; this.onBack = onBack; this.onOther = onOther;
            ink.setColor(color); ink.setStyle(Paint.Style.FILL);
            stroke.setColor(color); stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND); stroke.setStrokeWidth(2 * d);
            setClickable(true);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), cy = h / 2f;
            if (threeButton) {
                float size = Math.min(h * 0.34f, 14 * d);
                float back = w * 0.25f, home = w * 0.5f, recents = w * 0.75f;
                Path triangle = new Path();
                triangle.moveTo(back + size * 0.8f, cy - size); triangle.lineTo(back + size * 0.8f, cy + size); triangle.lineTo(back - size * 0.9f, cy); triangle.close();
                canvas.drawPath(triangle, ink);
                canvas.drawCircle(home, cy, size, ink);
                canvas.drawRoundRect(new RectF(recents - size * 0.9f, cy - size * 0.9f, recents + size * 0.9f, cy + size * 0.9f), 2 * d, 2 * d, ink);
            } else {
                float pw = w * 0.30f, py = h * 0.6f;
                canvas.drawRoundRect(new RectF(w / 2 - pw / 2, py - 2 * d, w / 2 + pw / 2, py + 2 * d), 2 * d, 2 * d, ink);
                float x = 24 * d, r = Math.min(h * 0.22f, 7 * d);
                Path chevron = new Path();
                chevron.moveTo(x + r * 0.6f, cy - r); chevron.lineTo(x - r * 0.6f, cy); chevron.lineTo(x + r * 0.6f, cy + r);
                canvas.drawPath(chevron, stroke);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (event.getAction() == MotionEvent.ACTION_UP) {
                performClick();
                float x = event.getX(), w = getWidth();
                if (x < w * (threeButton ? 0.375f : 0.25f)) onBack.run(); else onOther.run();
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override public boolean performClick() { return super.performClick(); }
    }

    // ------------------------------------------------------------------ phone design

    /** One screen of the stack: the view tree and the facts the phone needs to draw around it. */
    private static final class Entry {
        final ProjectPreview.Prepared prepared; final FrameLayout screen, content; final boolean camera, landscape; final int statusPx;
        Entry(ProjectPreview.Prepared prepared, FrameLayout screen, FrameLayout content, boolean camera, boolean landscape, int statusPx) {
            this.prepared = prepared; this.screen = screen; this.content = content; this.camera = camera; this.landscape = landscape; this.statusPx = statusPx;
        }
    }

    /** Full-screen video of a WebView; Back leaves it. */
    private static final class FullscreenFrame extends FrameLayout {
        Runnable exit = () -> { };
        FullscreenFrame(Context context) { super(context); }
    }

    /**
     * A phone with the size of this device, scaled to fit the available space. The scale does not change what the layout sees (dp sizes stay the same).
     * It holds the stack of screens and follows the soft keyboard like a real window does: the content shrinks above the keyboard.
     */
    public static final class PhoneView extends ViewGroup {
        private final Activity activity; private final TipListener tips; private final float d; private final int deviceW, deviceH;
        private final Deque<Entry> stack = new ArrayDeque<>();
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), button = new Paint(Paint.ANTI_ALIAS_FLAG), camera = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float scale = 1f; private int keyboardTop = -1;

        PhoneView(Activity activity, TipListener tips) {
            super(activity);
            this.activity = activity; this.tips = tips;
            d = activity.getResources().getDisplayMetrics().density;
            int[] size = deviceSizePx(activity);
            deviceW = size[0]; deviceH = size[1];
            body.setColor(0xFF121216); edge.setColor(0xFF3C3C48); edge.setStyle(Paint.Style.STROKE);
            button.setColor(0xFF2A2A33); camera.setColor(0xFF000000);
            setWillNotDraw(false);
        }

        // ---- the stack

        List<View> allScreens() {
            List<View> screens = new ArrayList<>();
            for (Entry entry : stack) screens.add(entry.screen);
            return screens;
        }

        void push(ProjectPreview.Prepared prepared) {
            if (stack.size() >= MAX_SCREENS) { tips.onTip("Too many screens are open in the preview. Go back first."); return; }
            Entry next = build(prepared);
            Entry old = stack.peek();
            if (old != null) removeView(old.screen);
            stack.push(next);
            attach(next);
        }

        /**
         * Back like on the phone: leaves full-screen video, else returns to the previous screen.
         * False when there is nothing to go back to (the first screen), so the caller can leave the preview.
         */
        public boolean goBack() {
            Entry top = stack.peek();
            if (top == null) return false;
            View last = top.screen.getChildCount() == 0 ? null : top.screen.getChildAt(top.screen.getChildCount() - 1);
            if (last instanceof FullscreenFrame) { ((FullscreenFrame) last).exit.run(); return true; }
            if (stack.size() <= 1) return false;
            Entry gone = stack.pop();
            removeView(gone.screen);
            List<WebView> webs = new ArrayList<>();
            collectWebViews(gone.screen, webs);
            for (WebView web : webs) destroy(web);
            attach(stack.peek());
            return true;
        }

        public int depth() { return stack.size(); }

        /** Back button of the preview's own navigation bar: works even when the app has no back button. */
        private void navigateBack() {
            if (!goBack()) tips.onTip("This is the first screen. On a real phone Back would close the app here.");
        }

        void open(String className) {
            Entry top = stack.peek();
            if (top == null) return;
            ProjectPreview.Prepared next = ProjectPreview.navigate(top.prepared, className);
            if (next == null) { tips.onTip(className + " is not an Activity of this project, so the preview cannot open it."); return; }
            if (next.error != null) { tips.onTip(next.error); return; }
            push(next);
            if (next.warning != null) tips.onTip(next.warning);
            else if (next.landscape && next.orientationInfo != null) tips.onTip(next.orientationInfo);
        }

        void perform(PreviewLinks.Action action, ProjectPreview.Prepared from, View root) {
            applyEffects(root, from, action.effects, true);
            if (action.toast != null) tips.onTip("Toast: " + toastText(action.toast, from));
            if (action.activity != null) open(action.activity);
            else if (action.finish) navigateBackOrExplain();
        }

        private void navigateBackOrExplain() {
            if (!goBack()) tips.onTip("The code calls finish() here. On the first screen that closes the app on a real phone.");
        }

        private static String toastText(String text, ProjectPreview.Prepared from) {
            if (!text.startsWith("@string/")) return text;
            int id = ProjectPreview.findIdentifier(from.resources, text.substring("@string/".length()), "string", from.packages);
            try { if (id != 0) return from.resources.getString(id); } catch (RuntimeException ignored) { }
            return text;
        }

        // ---- keyboard

        /** Y of the top of the soft keyboard in window coordinates, or a value <= 0 when it is hidden. */
        public void setKeyboardTop(int y) {
            if (y == keyboardTop) return;
            keyboardTop = y;
            post(this::applyKeyboard);
        }

        private void applyKeyboard() {
            Entry e = stack.peek();
            if (e == null || e.content.getHeight() <= 0) return;
            int pad = 0;
            if (keyboardTop > 0) {
                int[] at = new int[2];
                e.content.getLocationInWindow(at);
                float bottom = at[1] + e.content.getHeight() * scale;
                float overlap = bottom - keyboardTop;
                if (overlap > 0) pad = Math.min(Math.round(overlap / scale), Math.max(0, e.content.getHeight() - Math.round(96 * d)));
            }
            if (e.content.getPaddingBottom() != pad) e.content.setPadding(0, 0, 0, pad);
        }

        // ---- building a screen

        private Entry build(ProjectPreview.Prepared p) {
            final PreviewBars bars = p.bars;
            FrameLayout screen = new FrameLayout(activity);
            screen.setBackground(windowBackground(p));
            FrameLayout content = new FrameLayout(activity);
            fillContent(activity, p, content, screen, tips, this);

            int statusPx = systemDimension("status_bar_height", Math.round(24 * d));
            boolean threeButton = threeButtonNavigation(activity);
            int navPx = threeButton ? systemDimension("navigation_bar_height", Math.round(48 * d)) : systemDimension("navigation_bar_gesture_height", Math.round(20 * d));
            int actionPx = actionBarHeight(activity, p);
            // video: the source found in the code is played by VideoViews (layout, PiP window); the media card drives the first one
            final PreviewVideo.Source video = PreviewVideo.find(p.session);
            final List<VideoView> players = video == null ? new ArrayList<VideoView>() : PreviewVideo.attach(content, video, tips);
            final View pip = PreviewPip.supported(p.session) ? PreviewPip.build(activity, video, tips) : null;
            final VideoView controlled = !players.isEmpty() ? players.get(0) : PreviewPip.video(pip);
            final PreviewShade.Info shadeInfo = PreviewShade.scan(p.session);
            final View shade = shadeInfo.isEmpty() ? null : PreviewShade.build(activity, shadeInfo, p.session, statusPx, bars.showNav ? navPx : 0, controlled);
            final Runnable onHome = pip == null ? null : () -> {
                boolean first = pip.getVisibility() != View.VISIBLE;
                for (VideoView other : players) other.pause();
                PreviewPip.enter(pip);
                if (first) tips.onTip("Picture-in-picture (android:supportsPictureInPicture is true in the manifest). Drag the window; tap it to go back to full screen.");
            };
            Runnable back = this::navigateBack;
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            int topInset = 0, bottomInset = 0;
            final View[] statusBarRef = new View[1];
            final View[] navBarRef = new View[1];
            if (bars.edgeToEdge) {
                if (bars.actionBar) {
                    int extra = bars.showStatus ? statusPx : 0;
                    column.addView(actionBar(activity, bars, extra, tips, back), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actionPx + extra));
                } else if (bars.showStatus) topInset = statusPx;
                if (bars.showNav) bottomInset = navPx;
                column.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                screen.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (bars.showStatus) {
                    statusBarRef[0] = statusBar(activity, bars, Color.TRANSPARENT, tips, shade, shadeInfo.vpn);
                    screen.addView(statusBarRef[0], new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusPx, Gravity.TOP));
                }
                if (bars.showNav) {
                    navBarRef[0] = navBar(activity, bars, Color.TRANSPARENT, threeButton, back, tips, onHome);
                    screen.addView(navBarRef[0], new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, navPx, Gravity.BOTTOM));
                }
                dispatchInsets(content, topInset, bottomInset);
            } else {
                if (bars.showStatus) {
                    statusBarRef[0] = statusBar(activity, bars, bars.statusColor, tips, shade, shadeInfo.vpn);
                    column.addView(statusBarRef[0], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusPx));
                }
                if (bars.actionBar) column.addView(actionBar(activity, bars, 0, tips, back), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actionPx));
                column.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                if (bars.showNav) {
                    navBarRef[0] = navBar(activity, bars, bars.navColor, threeButton, back, tips, onHome);
                    column.addView(navBarRef[0], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, navPx));
                }
                screen.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            // In immersive/full-screen projects, Android can temporarily reveal the hidden bars
            // with a swipe from the corresponding screen edge. Emulate that in the preview too.
            if (bars.transientBars && !bars.showStatus) {
                View transientStatus = statusBarRef[0];
                if (transientStatus == null) transientStatus = statusBar(activity, bars,
                        bars.edgeToEdge ? Color.TRANSPARENT : bars.statusColor, tips, shade, shadeInfo.vpn);
                installTransientBar(screen, transientStatus, true, statusPx, d);
            }
            if (bars.transientBars && !bars.showNav) {
                View transientNav = navBarRef[0];
                if (transientNav == null) transientNav = navBar(activity, bars,
                        bars.edgeToEdge ? Color.TRANSPARENT : bars.navColor, threeButton, back, tips, onHome);
                installTransientBar(screen, transientNav, false, navPx, d);
            }
            if (pip != null) screen.addView(pip, PreviewPip.params(d, bars.showNav ? navPx : 0));
            if (shade != null) screen.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new Entry(p, screen, content, bars.showStatus && !p.landscape, p.landscape, statusPx);
        }

        /** Adds a hidden system bar which can be revealed with an inward edge swipe, then hides again. */
        private static void installTransientBar(FrameLayout screen, View bar, boolean top, int barHeightPx, float density) {
            bar.setVisibility(View.GONE);
            bar.setAlpha(1f);
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, barHeightPx, top ? Gravity.TOP : Gravity.BOTTOM);
            screen.addView(bar, barParams);

            final View edge = new View(screen.getContext());
            edge.setBackgroundColor(Color.TRANSPARENT);
            edge.setClickable(true);
            FrameLayout.LayoutParams edgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(Math.round(28 * density), barHeightPx / 2),
                    top ? Gravity.TOP : Gravity.BOTTOM);
            screen.addView(edge, edgeParams);

            final float[] downY = new float[1];
            final int slop = ViewConfiguration.get(screen.getContext()).getScaledTouchSlop();
            final Runnable hide = new Runnable() {
                @Override public void run() {
                    if (bar.getVisibility() != View.VISIBLE) return;
                    bar.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                        bar.setVisibility(View.GONE);
                        bar.setAlpha(1f);
                        edge.setVisibility(View.VISIBLE);
                    }).start();
                }
            };
            edge.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY[0] = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dy = event.getRawY() - downY[0];
                        boolean inward = top ? dy > slop : dy < -slop;
                        if (inward) {
                            screen.removeCallbacks(hide);
                            edge.setVisibility(View.GONE);
                            bar.setAlpha(0f);
                            bar.setVisibility(View.VISIBLE);
                            bar.animate().alpha(1f).setDuration(140).start();
                            screen.postDelayed(hide, 2600L);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return true;
                }
            });
        }

        private void attach(Entry entry) {
            entry.screen.setClipToOutline(true);
            entry.screen.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), (CORNER - BEZEL) * d); }
            });
            addView(entry.screen, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            requestLayout(); invalidate();
            post(this::applyKeyboard);
        }

        // ---- measuring and drawing

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            Entry e = stack.peek();
            boolean landscape = e != null && e.landscape;
            int screenW = landscape ? deviceH : deviceW, screenH = landscape ? deviceW : deviceH;
            float fullW = screenW + 2 * BEZEL * d, fullH = screenH + 2 * BEZEL * d;
            float availW = MeasureSpec.getSize(widthSpec), availH = MeasureSpec.getSize(heightSpec);
            scale = Math.min(Math.min(availW / fullW, availH / fullH), 1.6f);
            if (!(scale > 0f)) scale = 1f;
            if (e != null) e.screen.measure(MeasureSpec.makeMeasureSpec(screenW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.EXACTLY));
            setMeasuredDimension(Math.round(fullW * scale), Math.round(fullH * scale));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            Entry e = stack.peek();
            if (e == null) return;
            int inset = Math.round(BEZEL * d * scale);
            e.screen.layout(inset, inset, inset + e.screen.getMeasuredWidth(), inset + e.screen.getMeasuredHeight());
            e.screen.setPivotX(0f); e.screen.setPivotY(0f); e.screen.setScaleX(scale); e.screen.setScaleY(scale);
            if (changed) post(this::applyKeyboard);
        }

        @Override protected void onDraw(Canvas canvas) {
            Entry e = stack.peek();
            boolean landscape = e != null && e.landscape;
            float w = getWidth(), h = getHeight(), s = d * scale;
            RectF outer;
            if (landscape) {
                canvas.drawRoundRect(new RectF(w * 0.20f, 0, w * 0.28f, 2 * s), 1.5f * s, 1.5f * s, button);
                canvas.drawRoundRect(new RectF(w * 0.31f, 0, w * 0.41f, 2 * s), 1.5f * s, 1.5f * s, button);
                outer = new RectF(0, 1.5f * s, w, h);
            } else {
                canvas.drawRoundRect(new RectF(w - 2 * s, h * 0.20f, w, h * 0.28f), 1.5f * s, 1.5f * s, button);
                canvas.drawRoundRect(new RectF(w - 2 * s, h * 0.31f, w, h * 0.41f), 1.5f * s, 1.5f * s, button);
                outer = new RectF(0, 0, w - 1.5f * s, h);
            }
            canvas.drawRoundRect(outer, CORNER * s, CORNER * s, body);
            edge.setStrokeWidth(1.5f * s);
            canvas.drawRoundRect(new RectF(outer.left + 0.75f * s, outer.top + 0.75f * s, outer.right - 0.75f * s, outer.bottom - 0.75f * s), CORNER * s, CORNER * s, edge);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            Entry e = stack.peek();
            if (e != null && e.camera) canvas.drawCircle(getWidth() / 2f, BEZEL * d * scale + e.statusPx * scale / 2f, Math.min(5 * d, e.statusPx * 0.22f) * scale, camera);
        }
    }

    // ------------------------------------------------------------------ inflating the project's layout

    /** Context for inflating: the project's resources and theme, nothing of Studio Mobile's own theme leaks in. */
    private static final class PreviewContext extends ContextWrapper {
        private final Resources resources; private final Resources.Theme theme; private final Context application;
        PreviewContext(Context base, Resources resources, Resources.Theme theme) {
            super(base); this.resources = resources; this.theme = theme; this.application = base.getApplicationContext();
        }
        @Override public Resources getResources() { return resources; }
        @Override public AssetManager getAssets() { return resources.getAssets(); }
        @Override public Resources.Theme getTheme() { return theme; }
        @Override public void setTheme(int resId) { }
        @Override public Object getSystemService(String name) {
            // A plain inflater (no AppCompat factory from the Activity), bound to this context.
            if (LAYOUT_INFLATER_SERVICE.equals(name)) return LayoutInflater.from(application).cloneInContext(this);
            return super.getSystemService(name);
        }
    }

    private static final Map<String, String> FRAMEWORK_EQUIVALENT = new HashMap<>();
    static {
        String[][] pairs = {
            {"androidx.appcompat.widget.AppCompatTextView", "android.widget.TextView"}, {"androidx.appcompat.widget.AppCompatEditText", "android.widget.EditText"},
            {"androidx.appcompat.widget.AppCompatButton", "android.widget.Button"}, {"androidx.appcompat.widget.AppCompatImageView", "android.widget.ImageView"},
            {"androidx.appcompat.widget.AppCompatImageButton", "android.widget.ImageButton"}, {"androidx.appcompat.widget.AppCompatCheckBox", "android.widget.CheckBox"},
            {"androidx.appcompat.widget.AppCompatRadioButton", "android.widget.RadioButton"}, {"androidx.appcompat.widget.AppCompatSpinner", "android.widget.Spinner"},
            {"androidx.appcompat.widget.AppCompatSeekBar", "android.widget.SeekBar"}, {"androidx.appcompat.widget.AppCompatRatingBar", "android.widget.RatingBar"},
            {"androidx.appcompat.widget.AppCompatToggleButton", "android.widget.ToggleButton"}, {"androidx.appcompat.widget.AppCompatAutoCompleteTextView", "android.widget.AutoCompleteTextView"},
            {"androidx.appcompat.widget.AppCompatCheckedTextView", "android.widget.CheckedTextView"}, {"androidx.appcompat.widget.SwitchCompat", "android.widget.Switch"},
            {"com.google.android.material.switchmaterial.SwitchMaterial", "android.widget.Switch"}, {"androidx.appcompat.widget.LinearLayoutCompat", "android.widget.LinearLayout"},
            {"androidx.appcompat.widget.Toolbar", "android.widget.Toolbar"}, {"com.google.android.material.appbar.MaterialToolbar", "android.widget.Toolbar"},
            {"com.google.android.material.button.MaterialButton", "android.widget.Button"}, {"com.google.android.material.checkbox.MaterialCheckBox", "android.widget.CheckBox"},
            {"com.google.android.material.radiobutton.MaterialRadioButton", "android.widget.RadioButton"}, {"com.google.android.material.textview.MaterialTextView", "android.widget.TextView"},
            {"com.google.android.material.textfield.TextInputEditText", "android.widget.EditText"}, {"androidx.core.widget.NestedScrollView", "android.widget.ScrollView"},
            {"androidx.cardview.widget.CardView", "android.widget.FrameLayout"}, {"com.google.android.material.card.MaterialCardView", "android.widget.FrameLayout"},
            {"com.google.android.material.materialswitch.MaterialSwitch", "android.widget.Switch"}, {"com.google.android.material.slider.Slider", "android.widget.SeekBar"},
            {"com.google.android.material.textfield.MaterialAutoCompleteTextView", "android.widget.AutoCompleteTextView"}, {"androidx.appcompat.widget.SearchView", "android.widget.SearchView"},
            {"androidx.appcompat.widget.AppCompatMultiAutoCompleteTextView", "android.widget.MultiAutoCompleteTextView"}, {"com.google.android.material.imageview.ShapeableImageView", "android.widget.ImageView"},
            {"com.google.android.material.button.MaterialButtonToggleGroup", "android.widget.LinearLayout"}, {"androidx.coordinatorlayout.widget.CoordinatorLayout", "android.widget.FrameLayout"},
            {"com.google.android.material.appbar.CollapsingToolbarLayout", "android.widget.FrameLayout"}, {"androidx.swiperefreshlayout.widget.SwipeRefreshLayout", "android.widget.FrameLayout"}
        };
        for (String[] pair : pairs) FRAMEWORK_EQUIVALENT.put(pair[0], pair[1]);
    }

    /**
     * Framework widgets are created as they are. AppCompat/Material widgets that are plain subclasses use their framework equivalent.
     * Any other class from a library or from the project (its code is not loaded) becomes a labelled box that still holds its children.
     */
    private static final class PreviewFactory implements LayoutInflater.Factory2 {
        private final Activity activity; private final ProjectPreview.Prepared prepared; private final FrameLayout overlay; private final TipListener tips;
        /** What stand-ins need to inflate list items, headers and so on with these same rules. */
        final PreviewStandIns.Env env;
        /** The project's context, set before inflating (the inflater clones it for every nested layout). */
        Context previewContext;
        private int itemDepth;
        /** Classes that could only be drawn as a dashed box, with how many times. */
        private final Map<String, Integer> boxes = new LinkedHashMap<>();
        PreviewFactory(Activity activity, ProjectPreview.Prepared prepared, FrameLayout overlay, TipListener tips) {
            this.activity = activity; this.prepared = prepared; this.overlay = overlay; this.tips = tips;
            this.env = new PreviewStandIns.Env(prepared.resources, prepared.packages, prepared.session == null ? null : prepared.session.resDir, this::inflateItem);
        }

        private View inflateItem(int layoutId, ViewGroup parent) {
            if (previewContext == null || itemDepth >= 3) return null;
            itemDepth++;
            try {
                LayoutInflater nested = LayoutInflater.from(previewContext);
                nested.setFactory2(this);
                return nested.inflate(layoutId, parent, false);
            } finally { itemDepth--; }
        }

        private View box(Context context, String className) {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            Integer seen = boxes.get(simple);
            boxes.put(simple, seen == null ? 1 : seen + 1);
            return new Placeholder(context, className);
        }

        /** One line for the tip card when some widgets are only boxes; null when everything was drawn. */
        String unsupportedSummary() {
            if (boxes.isEmpty()) return null;
            StringBuilder names = new StringBuilder();
            int shown = 0, total = 0;
            for (Map.Entry<String, Integer> entry : boxes.entrySet()) {
                total += entry.getValue();
                if (shown++ < 4) { if (names.length() > 0) names.append(", "); names.append(entry.getKey()); }
            }
            if (boxes.size() > 4) names.append(", ...");
            return total + (total == 1 ? " widget is" : " widgets are") + " shown as a dashed box (" + names + "): the preview cannot draw them. Use Build to see them.";
        }

        @Override public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
            try {
                if (name.equals("WebView") || name.equals("android.webkit.WebView")) {
                    WebView web = new WebView(activity);   // WebView needs a real Activity context, not the project's resources
                    int id = attrs.getIdAttributeResourceValue(View.NO_ID);
                    if (id != View.NO_ID) web.setId(id);
                    configureWebView(activity, web, prepared, overlay, tips);
                    return web;
                }
                if ((name.equals("androidx.media3.ui.PlayerView") || name.equals("com.google.android.exoplayer2.ui.PlayerView") || name.equals("com.google.android.exoplayer2.ui.StyledPlayerView"))
                    && PreviewVideo.find(prepared.session) != null) return new VideoView(context, attrs);   // stand-in for the project's player: the platform VideoView really plays the source
                View standIn = PreviewStandIns.create(name, context, attrs, env);   // ConstraintLayout, RecyclerView, TabLayout, FAB, ...
                if (standIn != null) return standIn;
                List<java.io.File> sources = prepared.session == null ? null : prepared.session.sources;
                if (name.equals("fragment") || name.endsWith(".FragmentContainerView")) {   // the layout the fragment inflates
                    View shown = PreviewCustom.fragment(context, attrs, env, sources);
                    if (shown != null) return shown;
                }
                if (name.indexOf('.') < 0) return name.equals("fragment") ? box(context, "fragment") : null;
                if (name.startsWith("android.")) return null;
                String equivalent = FRAMEWORK_EQUIVALENT.get(name);
                if (equivalent != null) {
                    View view = Class.forName(equivalent).asSubclass(View.class).getConstructor(Context.class, AttributeSet.class).newInstance(context, attrs);
                    PreviewWidgets.style(view, name, attrs, context, prepared.resources, prepared.packages);
                    return view;
                }
                View custom = PreviewCustom.view(name, context, attrs, sources, (n, c, a) -> onCreateView(null, n, c, a));   // the project's own view, drawn as the class it extends
                if (custom != null) return custom;
            } catch (Throwable failure) { /* fall through to the box */ }
            return box(context, name);
        }
        @Override public View onCreateView(String name, Context context, AttributeSet attrs) { return onCreateView(null, name, context, attrs); }
    }

    /** A class the preview cannot load (custom view, library widget): a dashed box in the theme's accent color that still holds its children. */
    private static final class Placeholder extends LinearLayout {
        Placeholder(Context context, String className) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            setOrientation(VERTICAL);
            int pad = Math.round(6 * d);
            setPadding(pad, pad, pad, pad);
            setMinimumHeight(Math.round(36 * d));
            int accent = themeColor(context, android.R.attr.colorAccent, themeColor(context, android.R.attr.textColorSecondary, Color.GRAY));
            GradientDrawable box = new GradientDrawable();
            box.setColor(ColorUtils.setAlphaComponent(accent, 0x14)); box.setCornerRadius(6 * d); box.setStroke(Math.round(1 * d), ColorUtils.setAlphaComponent(accent, 0x99), 4 * d, 3 * d);
            setBackground(box);
            TextView label = new TextView(context);
            label.setText(className.substring(className.lastIndexOf('.') + 1));
            label.setTextSize(11); label.setSingleLine(true); label.setTextColor(accent);
            addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
    }

    private static int themeColor(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        try {
            if (context.getTheme().resolveAttribute(attr, value, true)) {
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
                if (value.resourceId != 0) return context.getResources().getColor(value.resourceId, context.getTheme());
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    // ------------------------------------------------------------------ WebView

    private static void configureWebView(Activity activity, WebView web, ProjectPreview.Prepared prepared, FrameLayout overlay, TipListener tips) {
        boolean assets = prepared.assetsDir != null && prepared.startPage != null;
        if (!assets && prepared.startUrl == null) return;
        WebSettings settings = web.getSettings();
        settings.setMediaPlaybackRequiresUserGesture(!prepared.autoplay);
        settings.setJavaScriptEnabled(prepared.javaScript);
        settings.setDomStorageEnabled(prepared.domStorage);
        settings.setAllowFileAccess(false);        // file:///android_asset and file:///android_res stay reachable, like on the device
        settings.setAllowContentAccess(false);
        final String page = assets ? Uri.encode(prepared.startPage, "/") : null;
        web.setWebViewClient(assets ? new AssetClient(activity.getApplicationContext(), prepared.assetsDir, prepared.resources, prepared.packages, prepared.fileScheme, page, tips) : new WebViewClient());
        final FullscreenFrame[] fullscreen = new FullscreenFrame[1];
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new MaterialAlertDialogBuilder(activity).setMessage(message).setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm()).setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new MaterialAlertDialogBuilder(activity).setMessage(message)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm()).setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            /** JavaScript errors are the first thing a page author needs; show them instead of leaving a blank page. */
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    String source = message.sourceId() == null ? "" : message.sourceId().substring(message.sourceId().lastIndexOf('/') + 1);
                    tips.onTip("JavaScript error: " + message.message() + (source.isEmpty() ? "" : "\n(" + source + ":" + message.lineNumber() + ")"));
                }
                return true;
            }
            /** Full-screen video: shown over the phone's screen; Back leaves it. */
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen[0] != null) { callback.onCustomViewHidden(); return; }
                FullscreenFrame frame = new FullscreenFrame(activity);
                frame.setBackgroundColor(Color.BLACK);
                frame.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                frame.exit = () -> { overlay.removeView(frame); fullscreen[0] = null; callback.onCustomViewHidden(); };
                fullscreen[0] = frame;
                overlay.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            @Override public void onHideCustomView() { if (fullscreen[0] != null) fullscreen[0].exit.run(); }
            /** Without a poster some WebView versions crash when a video starts. */
            @Override public Bitmap getDefaultVideoPoster() { return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); }
        });
        if (!assets) web.loadUrl(prepared.startUrl);
        else if (prepared.fileScheme) web.loadUrl("file:///android_asset/" + page);
        else web.loadUrl(ORIGIN + "/assets/" + page);
    }

    /**
     * Serves the project's files. The page is loaded either like the app's code does (file:///android_asset/..., where fetch() fails as on the device)
     * or under a virtual https origin (like WebViewAssetLoader). Besides assets/, res/raw and res/drawable are served (file:///android_res/..., /res/...).
     */
    private static final class AssetClient extends WebViewClient {
        private final File assets; private final Resources resources; private final String[] packages;
        private final boolean fileMode; private final String page; private final TipListener tips; private boolean fellBack; private final Context appContext;

        AssetClient(Context appContext, File assets, Resources resources, String[] packages, boolean fileMode, String page, TipListener tips) {
            this.appContext = appContext; this.assets = assets; this.resources = resources; this.packages = packages; this.fileMode = fileMode; this.page = page; this.tips = tips;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme(), path = uri.getPath() == null ? "" : uri.getPath();
            boolean cors = !fileMode;       // a file:// page has no CORS rights on the device either
            String assetPath = null, resPath = null;
            if (HOST.equals(uri.getHost()) && (scheme.equals("https") || scheme.equals("http"))) {
                if (path.startsWith("/assets/")) assetPath = path.substring("/assets/".length());
                else if (path.startsWith("/res/")) resPath = path.substring("/res/".length());
                else return response("text/plain", 404, new ByteArrayInputStream(new byte[0]), false, true);
            } else if (scheme.equals("file")) {
                if (path.startsWith("/android_asset/")) assetPath = path.substring("/android_asset/".length());
                else if (path.startsWith("/android_res/")) resPath = path.substring("/android_res/".length());
                else return null;
            } else return null;
            try {
                if (resPath != null) return resource(resPath, cors);
                String relative = assetPath.isEmpty() ? "index.html" : assetPath;
                File file = new File(assets, relative);
                String base = assets.getCanonicalPath() + File.separator;
                if (file.isDirectory()) file = new File(file, "index.html");
                String generated = PreviewJsx.generated(appContext, assets, relative);   // x.js from x.jsx/.tsx/.ts, and import './x.css' / './x.json'
                if (generated != null) return response(mimeFor("x.js"), 200, new ByteArrayInputStream(generated.getBytes(java.nio.charset.StandardCharsets.UTF_8)), false, cors);
                if (!file.getCanonicalPath().startsWith(base) || !file.isFile()) return response("text/plain", 404, new ByteArrayInputStream(new byte[0]), false, cors);
                String range = request.getRequestHeaders() == null ? null : request.getRequestHeaders().get("Range");
                if (range != null) { WebResourceResponse partial = partial(file, range, cors); if (partial != null) return partial; }
                return response(mimeFor(file.getName()), 200, new FileInputStream(file), true, cors);
            } catch (Exception failure) { return response("text/plain", 500, new ByteArrayInputStream(new byte[0]), false, cors); }
        }

        /** res/raw/song.mp3, res/drawable/pic.png and similar: found through the compiled resources, so qualifiers and overrides behave like the app. */
        private WebResourceResponse resource(String path, boolean cors) {
            int slash = path.indexOf('/');
            if (slash <= 0) return response("text/plain", 404, new ByteArrayInputStream(new byte[0]), false, cors);
            String type = path.substring(0, slash), name = path.substring(slash + 1);
            if (type.contains("-")) type = type.substring(0, type.indexOf('-'));
            String entry = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            int id = ProjectPreview.findIdentifier(resources, entry, type, packages);
            if (id == 0) return response("text/plain", 404, new ByteArrayInputStream(new byte[0]), false, cors);
            try {
                TypedValue value = new TypedValue();
                resources.getValue(id, value, true);
                String source = value.string == null ? name : value.string.toString();
                if (source.endsWith(".xml")) return response("text/plain", 404, new ByteArrayInputStream(new byte[0]), false, cors);
                return response(mimeFor(source), 200, resources.openRawResource(id), false, cors);
            } catch (Exception unreadable) { return response("text/plain", 404, new ByteArrayInputStream(new byte[0]), false, cors); }
        }

        /** The project's code often uses file:///android_asset/...; with a virtual origin those navigations are pointed at the same files. */
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!fileMode && "file".equals(uri.getScheme()) && uri.getPath() != null && uri.getPath().startsWith("/android_asset/")) {
                view.loadUrl(ORIGIN + "/assets/" + Uri.encode(uri.getPath().substring("/android_asset/".length()), "/"));
                return true;
            }
            return false;
        }

        /** If this WebView refuses the file:// page, fall back to the virtual origin and say so, instead of showing an error page. */
        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (fileMode && !fellBack && request.isForMainFrame() && "file".equals(request.getUrl().getScheme())) {
                fellBack = true;
                view.loadUrl(ORIGIN + "/assets/" + page);
                tips.onTip("This WebView would not load file:///android_asset/ in the preview, so the page runs under a virtual https address. fetch() works here but not in the built app.");
            }
        }

        private static String reason(int status) { return status == 200 ? "OK" : status == 206 ? "Partial Content" : status == 404 ? "Not Found" : "Error"; }

        private static WebResourceResponse response(String mime, int status, InputStream body, boolean ranges, boolean cors) {
            Map<String, String> headers = new HashMap<>();
            if (cors) headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-store");
            if (ranges) headers.put("Accept-Ranges", "bytes");
            boolean text = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("xml");
            return new WebResourceResponse(mime, text ? "utf-8" : null, status, reason(status), headers, body);
        }

        /** Answers "Range: bytes=a-b" with 206, so audio and video can seek. Null when the header is not understood. */
        private static WebResourceResponse partial(File file, String header, boolean cors) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(header.trim());
            long total = file.length();
            if (!m.matches() || total <= 0) return null;
            long start = 0, end = 0;
            try {
                if (m.group(1).isEmpty()) { start = Math.max(0, total - Long.parseLong(m.group(2))); end = total - 1; }
                else { start = Long.parseLong(m.group(1)); end = m.group(2).isEmpty() ? total - 1 : Math.min(Long.parseLong(m.group(2)), total - 1); }
            } catch (NumberFormatException bad) { return null; }
            if (start > end || start >= total) return null;
            Map<String, String> headers = new HashMap<>();
            if (cors) headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-store");
            headers.put("Accept-Ranges", "bytes");
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
            headers.put("Content-Length", String.valueOf(end - start + 1));
            try { return new WebResourceResponse(mimeFor(file.getName()), null, 206, "Partial Content", headers, slice(file, start, end - start + 1)); }
            catch (java.io.IOException unreadable) { return null; }
        }

        private static InputStream slice(File file, long start, final long length) throws java.io.IOException {
            FileInputStream in = new FileInputStream(file);
            long skipped = 0;
            while (skipped < start) { long n = in.skip(start - skipped); if (n <= 0) break; skipped += n; }
            return new java.io.FilterInputStream(in) {
                private long left = length;
                @Override public int read() throws java.io.IOException {
                    if (left <= 0) return -1;
                    int b = super.read(); if (b >= 0) left--; return b;
                }
                @Override public int read(byte[] buffer, int offset, int count) throws java.io.IOException {
                    if (left <= 0) return -1;
                    int n = super.read(buffer, offset, (int) Math.min(count, left)); if (n > 0) left -= n; return n;
                }
            };
        }

        /** Media and web fonts first: the platform's table misses several of them on older Android versions. */
        private static String mimeFor(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            String extension = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : "";
            switch (extension) {
                case "html": case "htm": return "text/html";
                case "js": case "mjs": return "text/javascript";
                case "css": return "text/css";
                case "json": case "map": return "application/json";
                case "webmanifest": return "application/manifest+json";
                case "svg": return "image/svg+xml";
                case "wasm": return "application/wasm";
                case "txt": return "text/plain";
                case "xml": return "application/xml";
                case "ico": return "image/x-icon";
                case "webp": return "image/webp";
                case "avif": return "image/avif";
                case "mp3": return "audio/mpeg";
                case "m4a": case "aac": return "audio/mp4";
                case "ogg": case "oga": case "opus": return "audio/ogg";
                case "wav": return "audio/wav";
                case "flac": return "audio/flac";
                case "mp4": case "m4v": return "video/mp4";
                case "webm": return "video/webm";
                case "ogv": return "video/ogg";
                case "3gp": return "video/3gpp";
                case "woff": return "font/woff";
                case "woff2": return "font/woff2";
                case "ttf": return "font/ttf";
                case "otf": return "font/otf";
                default: {
                    String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    return guessed != null ? guessed : "application/octet-stream";
                }
            }
        }
    }
}
