package com.example.studiomobile.workspace;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The notification shade of the preview phone: tap or swipe the status bar and a panel opens with
 *  - the Quick Settings tiles of the project (every TileService in the manifest: label and icon), and
 *  - a media notification card (title, artist, previous / play / next, a seek bar) when the code uses MediaSession / MediaStyle / Media3, and
 *  - the VPN row at the bottom (key icon, "This device is connected to the internet through ...") when the project has a VpnService;
 *    tapping it opens the same explanation pop-up Android shows. The status bar gets the key icon too (see {@link #drawKey}).
 * It is a mock. Java is only read, never run, so nothing really plays; the controls just move so the layout can be judged.
 */
final class PreviewShade {
    private PreviewShade() { }

    static final class Tile { String label = "", iconType = "drawable", iconName = ""; }

    static final class Info {
        final List<Tile> tiles = new ArrayList<>();
        boolean media, vpn; String title = "", artist = "", app = "";
        Integer mediaColor = null;
        boolean isEmpty() { return tiles.isEmpty() && !media && !vpn; }
    }

    private static final Pattern SERVICE = Pattern.compile("(?s)<service\\b([^>]*)>(.*?)</service>");
    private static final Pattern APP_LABEL = Pattern.compile("<application\\b[^>]*?android:label\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ICON_REF = Pattern.compile("@(drawable|mipmap)/(\\w+)");
    private static final Pattern ICON_CODE = Pattern.compile("Icon\\s*\\.\\s*createWithResource\\s*\\([^,]+,\\s*(?:R\\s*\\.\\s*)?(drawable|mipmap)\\s*\\.\\s*(\\w+)");
    private static final Pattern LABEL_CODE = Pattern.compile("setLabel\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern TITLE_CODE = Pattern.compile("METADATA_KEY_TITLE\\s*,\\s*\"([^\"]+)\"|setContentTitle\\s*\\(\\s*\"([^\"]+)\"|\\.setTitle\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern ARTIST_CODE = Pattern.compile("METADATA_KEY_ARTIST\\s*,\\s*\"([^\"]+)\"|setContentText\\s*\\(\\s*\"([^\"]+)\"|\\.setArtist\\s*\\(\\s*\"([^\"]+)\"");

    private static final Pattern VPN_MANIFEST = Pattern.compile("android\\.net\\.VpnService|android\\.permission\\.BIND_VPN_SERVICE");
    private static final Pattern VPN_CODE = Pattern.compile("\\bextends\\s+VpnService\\b|:\\s*VpnService\\s*\\(");

    /** True when the project declares a VpnService (manifest) or has a class that extends it (code). */
    static boolean usesVpn(String manifest, String code) {
        return VPN_MANIFEST.matcher(manifest == null ? "" : manifest).find() || VPN_CODE.matcher(code == null ? "" : code).find();
    }

    // ------------------------------------------------------------------ reading the project

    static Info scan(ProjectPreview.Session s) {
        Info info = new Info();
        if (s == null) return info;
        String manifest = s.manifest == null ? "" : s.manifest;
        StringBuilder all = new StringBuilder();
        if (s.sources != null) for (File f : s.sources) { if (all.length() > 600000) break; all.append(read(f)).append('\n'); }
        String code = all.toString();

        Matcher app = APP_LABEL.matcher(manifest);
        info.app = app.find() ? text(s, app.group(1)) : "";
        if (info.app.isEmpty()) info.app = s.projectName == null ? "" : s.projectName;

        Matcher service = SERVICE.matcher(manifest);
        while (service.find()) {
            if (!service.group(2).contains("android.service.quicksettings.action.QS_TILE")) continue;
            String attrs = service.group(1);
            String className = simpleName(attr(attrs, "name"));
            String source = sourceOf(s, className);
            Tile tile = new Tile();
            tile.label = text(s, attr(attrs, "label"));
            if (tile.label.isEmpty() && source != null) { Matcher l = LABEL_CODE.matcher(source); if (l.find()) tile.label = l.group(1); }
            if (tile.label.isEmpty()) tile.label = className.isEmpty() ? "Tile" : className;
            Matcher icon = ICON_REF.matcher(attr(attrs, "icon"));
            if (icon.find()) { tile.iconType = icon.group(1); tile.iconName = icon.group(2); }
            else if (source != null) { Matcher c = ICON_CODE.matcher(source); if (c.find()) { tile.iconType = c.group(1); tile.iconName = c.group(2); } }
            info.tiles.add(tile);
        }

        info.vpn = usesVpn(manifest, code);
        info.media = code.contains("MediaStyle") || code.contains("MediaSession") || code.contains("androidx.media3")
            || manifest.contains("MediaSessionService") || manifest.contains("MediaLibraryService") || manifest.contains("MediaBrowserService")
            || manifest.contains("mediaPlayback");
        if (info.media) {
            info.title = firstGroup(TITLE_CODE, code);
            info.artist = firstGroup(ARTIST_CODE, code);
            if (info.title.isEmpty()) info.title = "Song title";
            if (info.artist.isEmpty()) info.artist = "Artist";
            // Read an explicitly configured notification color from the user's source.
            // Prefer setColor(...) on a notification builder; only literal colors are
            // resolved here, because arbitrary Java expressions cannot safely be executed.
            Matcher notificationBuilder = Pattern.compile(
                "(?s)(?:NotificationCompat\\.Builder|Notification\\.Builder)[^;]{0,2400}?\\.setColor\\s*\\(\\s*([^)]*)\\)"
            ).matcher(code);
            String colorExpr = notificationBuilder.find() ? notificationBuilder.group(1).trim() : "";
            if (!colorExpr.isEmpty()) {
                try {
                    Matcher hex = Pattern.compile("(?i)0x([0-9a-f]{6,8})").matcher(colorExpr);
                    Matcher parse = Pattern.compile("(?i)Color\\.parseColor\\s*\\(\\s*\"(#(?:[0-9a-f]{3,8}))\"\\s*\\)").matcher(colorExpr);
                    if (hex.find()) {
                        String value = hex.group(1);
                        long parsed = Long.parseLong(value, 16);
                        info.mediaColor = value.length() <= 6 ? (int)(0xFF000000L | parsed) : (int)parsed;
                    } else if (parse.find()) {
                        String value = parse.group(1);
                        if (value.length() == 4) value = "#" + value.charAt(1) + value.charAt(1) + value.charAt(2) + value.charAt(2) + value.charAt(3) + value.charAt(3);
                        if (value.length() == 7) value = "#FF" + value.substring(1);
                        info.mediaColor = (int)Long.parseLong(value.substring(1), 16);
                    } else if (colorExpr.matches("(?s).*\\bColor\\.GREEN\\b.*")) info.mediaColor = Color.GREEN;
                    else if (colorExpr.matches("(?s).*\\bColor\\.BLUE\\b.*")) info.mediaColor = Color.BLUE;
                    else if (colorExpr.matches("(?s).*\\bColor\\.RED\\b.*")) info.mediaColor = Color.RED;
                    else if (colorExpr.matches("(?s).*\\bColor\\.WHITE\\b.*")) info.mediaColor = Color.WHITE;
                    else if (colorExpr.matches("(?s).*\\bColor\\.BLACK\\b.*")) info.mediaColor = Color.BLACK;
                    else {
                        Matcher resource = Pattern.compile("R\\.color\\.(\\w+)").matcher(colorExpr);
                        if (resource.find() && s.resources != null) {
                            int id = ProjectPreview.findIdentifier(s.resources, resource.group(1), "color", s.packages);
                            if (id != 0) info.mediaColor = s.resources.getColor(id, null);
                        }
                    }
                } catch (RuntimeException ignored) { info.mediaColor = null; }
            }
        }
        return info;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return "";
        for (int i = 1; i <= m.groupCount(); i++) if (m.group(i) != null) return m.group(i);
        return "";
    }

    private static String attr(String attributes, String name) {
        Matcher m = Pattern.compile("android:" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(attributes);
        return m.find() ? m.group(1) : "";
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static String sourceOf(ProjectPreview.Session s, String className) {
        if (className.isEmpty() || s.sources == null) return null;
        for (File f : s.sources) {
            String n = f.getName();
            if (n.equals(className + ".java") || n.equals(className + ".kt")) return read(f);
        }
        return null;
    }

    private static String text(ProjectPreview.Session s, String ref) {
        if (ref == null || ref.isEmpty()) return "";
        if (!ref.startsWith("@")) return ref;
        if (!ref.startsWith("@string/") || s.resources == null) return "";
        int id = ProjectPreview.findIdentifier(s.resources, ref.substring("@string/".length()), "string", s.packages);
        if (id == 0) return "";
        try { return s.resources.getString(id); } catch (Exception missing) { return ""; }
    }

    private static String read(File file) {
        try { return file != null && file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : ""; }
        catch (Exception unreadable) { return ""; }
    }

    // ------------------------------------------------------------------ drawing the panel

    /** Builds the (hidden) panel. Add it on top of the phone screen and call {@link #show} to open it. */
    static View build(Context context, Info info, ProjectPreview.Session s, int statusPx, int bottomPx, VideoView video) {
        final float d = context.getResources().getDisplayMetrics().density;
        final FrameLayout root = new FrameLayout(context);
        root.setVisibility(View.GONE);

        View scrim = new View(context);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> hide(root));
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xFF1B1C1E);
        panelBg.setCornerRadii(new float[] {0, 0, 0, 0, 24 * d, 24 * d, 24 * d, 24 * d});
        panel.setBackground(panelBg);
        panel.setPadding(Math.round(14 * d), statusPx + Math.round(10 * d), Math.round(14 * d), Math.round(16 * d));
        panel.setClickable(true);
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout row = null;
        for (int i = 0; i < info.tiles.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = Math.round(10 * d);
                panel.addView(row, rowParams);
            }
            row.addView(tileView(context, info.tiles.get(i), s, d), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (row != null) for (int i = info.tiles.size() % 4; i != 0 && i < 4; i++) row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        if (info.media) panel.addView(mediaCard(context, info, root, d, video));
        if (info.vpn) {
            FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * d), Gravity.BOTTOM);
            rowParams.setMargins(Math.round(14 * d), 0, Math.round(14 * d), bottomPx + Math.round(12 * d));
            root.addView(vpnRow(context, info, root, d), rowParams);
        }
        return root;
    }

    // ------------------------------------------------------------------ VPN row and pop-up

    /** Draws the status bar's VPN key: a ring and a shaft with two teeth, {@code size} wide, centered on {@code cy}, in the stroke paint's color. */
    static void drawKey(Canvas canvas, Paint stroke, float left, float cy, float size) {
        float r = size * 0.22f, shaftEnd = left + size;
        canvas.drawCircle(left + r, cy, r, stroke);
        canvas.drawLine(left + 2 * r, cy, shaftEnd, cy, stroke);
        canvas.drawLine(shaftEnd - size * 0.12f, cy, shaftEnd - size * 0.12f, cy + size * 0.2f, stroke);
        canvas.drawLine(shaftEnd, cy, shaftEnd, cy + size * 0.2f, stroke);
    }

    private static final class KeyIcon extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG); private final float d;
        KeyIcon(Context context, int color) {
            super(context);
            d = context.getResources().getDisplayMetrics().density;
            stroke.setColor(color); stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeWidth(2 * d);
        }
        @Override protected void onDraw(Canvas canvas) { drawKey(canvas, stroke, (getWidth() - 22 * d) / 2f, getHeight() / 2f - 2 * d, 22 * d); }
    }

    /** The black pill at the bottom of the shade. Tapping it opens the explanation pop-up. */
    private static View vpnRow(Context context, Info info, FrameLayout root, float d) {
        final String app = info.app.isEmpty() ? "VPN" : info.app;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(32 * d);
        bg.setStroke(Math.round(1.5f * d), 0xFF5F6368);
        row.setBackground(bg);
        row.setClickable(true);
        row.setPadding(Math.round(14 * d), 0, Math.round(16 * d), 0);
        row.addView(new KeyIcon(context, Color.WHITE), new LinearLayout.LayoutParams(Math.round(32 * d), Math.round(32 * d)));
        TextView text = label(context, "This device is connected to the internet through " + app, 15, Color.WHITE, false);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = Math.round(10 * d);
        row.addView(text, textParams);
        TextView chevron = label(context, "\u203A", 26, Color.WHITE, false);
        row.addView(chevron);
        row.setOnClickListener(v -> showVpnDialog(context, root, app, d));
        return row;
    }

    /** The pop-up Android shows for the VPN row: who the VPN is, what the provider can see, a link to the VPN settings, and OK. */
    private static void showVpnDialog(Context context, final FrameLayout root, String app, float d) {
        final FrameLayout overlay = new FrameLayout(context);
        overlay.setBackgroundColor(0x66000000);
        overlay.setClickable(true);
        final Runnable close = () -> root.removeView(overlay);
        overlay.setOnClickListener(v -> close.run());

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B2726);
        bg.setCornerRadius(28 * d);
        card.setBackground(bg);
        card.setClickable(true);
        int pad = Math.round(24 * d);
        card.setPadding(pad, pad, pad, Math.round(18 * d));

        final int accent = 0xFF7FE3E0;
        String message = "This device is connected to the internet through " + app + ". Your network activity, including emails and browsing data, is visible to the VPN provider. ";
        String link = "Open VPN settings";
        SpannableStringBuilder body = new SpannableStringBuilder(message + link);
        body.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) { close.run(); }   // the preview has no system Settings app to open
            @Override public void updateDrawState(TextPaint paint) { paint.setColor(accent); paint.setUnderlineText(true); }
        }, message.length(), message.length() + link.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView content = new TextView(context);
        content.setText(body);
        content.setTextSize(16);
        content.setTextColor(0xFFE3E3E3);
        content.setLineSpacing(0, 1.1f);
        content.setMovementMethod(LinkMovementMethod.getInstance());
        card.addView(content);

        TextView ok = new TextView(context);
        ok.setText("OK");
        ok.setTextSize(16);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setTextColor(accent);
        ok.setPadding(Math.round(12 * d), Math.round(12 * d), Math.round(12 * d), Math.round(12 * d));
        ok.setOnClickListener(v -> close.run());
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END);
        okParams.topMargin = Math.round(28 * d);
        card.addView(ok, okParams);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        cardParams.setMargins(Math.round(14 * d), 0, Math.round(14 * d), 0);
        overlay.addView(card, cardParams);
        overlay.setAlpha(0f);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.animate().alpha(1f).setDuration(150).start();
    }

    private static View tileView(Context context, Tile tile, ProjectPreview.Session s, float d) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        final GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(0xFF3A3A3D);
        FrameLayout circle = new FrameLayout(context);
        circle.setBackground(circleBg);
        circle.setClickable(true);
        circle.setOnClickListener(v -> {
            boolean on = !Boolean.TRUE.equals(v.getTag());
            v.setTag(on);
            circleBg.setColor(on ? 0xFF0074D1 : 0xFF3A3A3D);
        });

        Drawable icon = null;
        if (!tile.iconName.isEmpty() && s.resources != null) {
            int id = ProjectPreview.findIdentifier(s.resources, tile.iconName, tile.iconType, s.packages);
            if (id != 0) { try { icon = s.resources.getDrawable(id, null); } catch (Exception unreadable) { icon = null; } }
        }
        if (icon != null) {
            icon = icon.mutate();
            ImageView image = new ImageView(context);
            image.setImageDrawable(icon);
            image.setColorFilter(Color.WHITE);   // the system only uses the shape of a tile icon and paints it itself
            circle.addView(image, new FrameLayout.LayoutParams(Math.round(26 * d), Math.round(26 * d), Gravity.CENTER));
        } else {
            TextView letter = new TextView(context);
            letter.setText(tile.label.isEmpty() ? "?" : tile.label.substring(0, 1).toUpperCase(Locale.ROOT));
            letter.setTextColor(Color.WHITE);
            letter.setTextSize(20);
            letter.setGravity(Gravity.CENTER);
            circle.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        box.addView(circle, new LinearLayout.LayoutParams(Math.round(56 * d), Math.round(56 * d)));

        TextView label = new TextView(context);
        label.setText(tile.label);
        label.setTextColor(0xFFE3E3E3);
        label.setTextSize(11);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Math.round(6 * d);
        box.addView(label, labelParams);
        return box;
    }

    private static View mediaCard(Context context, Info info, View root, float d, final VideoView video) {
        final int total = 225;   // 3:45, only for the mock
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        // Follow the notification color declared by the user's MediaStyle/Notification builder.
        // Keep the dark preview background only when the source has no readable color.
        cardBg.setColor(info.mediaColor != null ? darkenForMedia(info.mediaColor) : 0xFF2D2F33);
        cardBg.setCornerRadius(20 * d);
        card.setBackground(cardBg);
        card.setPadding(Math.round(16 * d), Math.round(14 * d), Math.round(16 * d), Math.round(10 * d));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = Math.round(4 * d);

        card.addView(label(context, info.app.isEmpty() ? "Media" : info.app, 12, 0xFFB0B3B8, false));
        card.addView(label(context, info.title, 17, Color.WHITE, true));
        card.addView(label(context, info.artist, 14, 0xFFC4C7CC, false));

        final SeekBar seek = new SeekBar(context);
        seek.setMax(total);
        seek.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        seek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seekParams.topMargin = Math.round(8 * d);
        card.addView(seek, seekParams);

        LinearLayout times = new LinearLayout(context);
        times.setOrientation(LinearLayout.HORIZONTAL);
        final TextView current = label(context, "0:00", 11, 0xFFB0B3B8, false);
        TextView end = label(context, clock(total), 11, 0xFFB0B3B8, false);
        end.setGravity(Gravity.END);
        times.addView(current, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(end, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(times);

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        ImageView previous = control(context, android.R.drawable.ic_media_previous, d);
        final ImageView play = control(context, android.R.drawable.ic_media_play, d);
        ImageView next = control(context, android.R.drawable.ic_media_next, d);
        controls.addView(previous);
        controls.addView(play);
        controls.addView(next);
        card.addView(controls);

        card.addView(label(context, video != null ? "Preview: these controls drive the video" : "Preview only: nothing really plays", 10, 0xFF8A8D93, false));

        final boolean[] playing = {false};
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!playing[0] || !root.isAttachedToWindow()) return;
            if (video != null) {
                int duration = video.getDuration();
                if (duration > 0 && seek.getMax() != duration / 1000) { seek.setMax(duration / 1000); end.setText(clock(duration / 1000)); }
                seek.setProgress(video.getCurrentPosition() / 1000);
            } else {
                int position = seek.getProgress() + 1;
                if (position > total) position = 0;
                seek.setProgress(position);
            }
            root.postDelayed(tick[0], 1000);
        };
        play.setOnClickListener(v -> {
            playing[0] = !playing[0];
            play.setImageResource(playing[0] ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            if (video != null) { if (playing[0]) video.start(); else video.pause(); }
            if (playing[0]) root.postDelayed(tick[0], 1000);
        });
        previous.setOnClickListener(v -> { seek.setProgress(0); if (video != null) video.seekTo(0); });
        next.setOnClickListener(v -> { seek.setProgress(0); if (video != null) video.seekTo(0); });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { current.setText(clock(progress)); if (fromUser && video != null) video.seekTo(progress * 1000); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        card.setLayoutParams(cardParams);
        return card;
    }

    /** Darkens a source notification color to keep white media controls readable. */
    private static int darkenForMedia(int color) {
        int alpha = Color.alpha(color);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0.35f, hsv[1]);
        hsv[2] = Math.min(0.32f, hsv[2] * 0.55f);
        return Color.HSVToColor(alpha, hsv);
    }

    private static TextView label(Context context, String text, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private static ImageView control(Context context, int drawable, float d) {
        ImageView view = new ImageView(context);
        view.setImageResource(drawable);
        view.setColorFilter(Color.WHITE);
        view.setClickable(true);
        int pad = Math.round(10 * d);
        view.setPadding(pad, pad, pad, pad);
        view.setLayoutParams(new LinearLayout.LayoutParams(Math.round(56 * d), Math.round(56 * d)));
        return view;
    }

    private static String clock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ------------------------------------------------------------------ opening and closing

    static void show(View panel) {
        panel.setAlpha(0f);
        panel.setVisibility(View.VISIBLE);
        panel.animate().alpha(1f).setDuration(150).start();
    }

    static void hide(View panel) {
        panel.animate().alpha(0f).setDuration(120).withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    /** A tap or a deliberate downward swipe on the status bar opens the panel. */
    static void attachOpener(View statusBar, View panel) {
        final float[] downY = new float[1];
        final float[] downX = new float[1];
        final boolean[] dragged = new boolean[1];
        final int slop = android.view.ViewConfiguration.get(statusBar.getContext()).getScaledTouchSlop();
        statusBar.setClickable(true);
        statusBar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = event.getRawY();
                    downX[0] = event.getRawX();
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - downY[0];
                    float dx = event.getRawX() - downX[0];
                    if (dy > slop && dy > Math.abs(dx)) dragged[0] = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    float finalDy = event.getRawY() - downY[0];
                    float finalDx = event.getRawX() - downX[0];
                    boolean swipeDown = finalDy > slop && finalDy > Math.abs(finalDx);
                    boolean tap = Math.abs(finalDy) <= slop && Math.abs(finalDx) <= slop;
                    if ((swipeDown || tap || dragged[0]) && panel.getVisibility() != View.VISIBLE) show(panel);
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragged[0] = false;
                    return true;
                default:
                    return true;
            }
        });
    }
}
