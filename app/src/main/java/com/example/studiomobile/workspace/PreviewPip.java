package com.example.studiomobile.workspace;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;
import java.util.regex.Pattern;

/**
 * The picture-in-picture window of the preview phone. It exists when the manifest declares android:supportsPictureInPicture="true".
 * Tap Home in the preview's navigation bar to enter PiP; drag the window around, tap it to go back to full screen.
 * It is a mock: Java is only read, never run, so no video plays.
 */
final class PreviewPip {
    private PreviewPip() { }

    private static final Pattern SUPPORTED = Pattern.compile("android:supportsPictureInPicture\\s*=\\s*\"true\"");
    private static final int WIDTH_DP = 200, HEIGHT_DP = 112;

    static boolean supported(ProjectPreview.Session s) {
        return s != null && s.manifest != null && SUPPORTED.matcher(s.manifest).find();
    }

    /** Where the window sits at first: bottom right, above the navigation bar. */
    static FrameLayout.LayoutParams params(float d, int bottomInsetPx) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.round(WIDTH_DP * d), Math.round(HEIGHT_DP * d), Gravity.BOTTOM | Gravity.END);
        params.rightMargin = Math.round(12 * d);
        params.bottomMargin = bottomInsetPx + Math.round(16 * d);
        return params;
    }

    /** Builds the (hidden) window. */
    static View build(Context context, PreviewVideo.Source video, PreviewRenderer.TipListener tips) {
        final float d = context.getResources().getDisplayMetrics().density;
        final FrameLayout window = new FrameLayout(context);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF111214);
        background.setCornerRadius(12 * d);
        window.setBackground(background);
        window.setElevation(8 * d);
        window.setClipToOutline(true);
        window.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * d); }
        });
        window.setVisibility(View.GONE);

        final VideoView player = video == null ? null : new VideoView(context);
        if (player != null) {
            PreviewVideo.configure(player, video, tips, false);
            window.addView(player, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            window.setTag(player);
        }

        final ImageView play = new ImageView(context);
        play.setImageResource(android.R.drawable.ic_media_pause);
        play.setColorFilter(Color.WHITE);
        play.setClickable(true);
        int pad = Math.round(8 * d);
        play.setPadding(pad, pad, pad, pad);
        play.setOnClickListener(v -> {
            boolean paused = !Boolean.TRUE.equals(v.getTag());
            v.setTag(paused);
            play.setImageResource(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause);
            if (player != null) { if (paused) player.pause(); else player.start(); }
        });
        window.addView(play, new FrameLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d), Gravity.CENTER));

        ImageView close = new ImageView(context);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setColorFilter(Color.WHITE);
        close.setClickable(true);
        int closePad = Math.round(6 * d);
        close.setPadding(closePad, closePad, closePad, closePad);
        close.setOnClickListener(v -> exit(window));
        window.addView(close, new FrameLayout.LayoutParams(Math.round(32 * d), Math.round(32 * d), Gravity.TOP | Gravity.END));

        TextView label = new TextView(context);
        label.setText("Picture-in-picture");
        label.setTextColor(0xCCFFFFFF);
        label.setTextSize(10);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        labelParams.leftMargin = Math.round(8 * d);
        labelParams.bottomMargin = Math.round(6 * d);
        window.addView(label, labelParams);

        // drag the window; a tap that did not move goes back to full screen
        final float slop = 6 * d;
        final float[] down = new float[2];
        final boolean[] moved = {false};
        window.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getX(); down[1] = event.getY(); moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - down[0], dy = event.getY() - down[1];
                    if (Math.abs(dx) + Math.abs(dy) > slop) moved[0] = true;
                    if (!moved[0]) return true;
                    View parent = (View) v.getParent();
                    if (parent == null) return true;
                    float x = v.getTranslationX() + dx, y = v.getTranslationY() + dy;
                    x = Math.max(-v.getLeft(), Math.min(parent.getWidth() - v.getRight(), x));
                    y = Math.max(-v.getTop(), Math.min(parent.getHeight() - v.getBottom(), y));
                    v.setTranslationX(x);
                    v.setTranslationY(y);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) exit(window);
                    return true;
                default:
                    return true;
            }
        });
        return window;
    }

    static void enter(View window) {
        if (window.getVisibility() == View.VISIBLE) return;
        window.setTranslationX(0f);
        window.setTranslationY(0f);
        window.setAlpha(0f);
        window.setVisibility(View.VISIBLE);
        window.animate().alpha(1f).setDuration(150).start();
        VideoView player = video(window);
        if (player != null) player.start();
    }

    /** The video of the window, or null (also when there is no window). */
    static VideoView video(View window) {
        Object tag = window == null ? null : window.getTag();
        return tag instanceof VideoView ? (VideoView) tag : null;
    }

    static void exit(View window) {
        VideoView player = video(window);
        if (player != null) player.pause();
        window.animate().alpha(0f).setDuration(120).withEndAction(() -> window.setVisibility(View.GONE)).start();
    }
}
