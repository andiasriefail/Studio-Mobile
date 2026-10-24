package com.example.studiomobile.workspace;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the picture chosen in the Create Project wizard into the same resource files Android Studio's Image Asset tool writes:
 *
 * <pre>
 * app/src/main/res/
 *   mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml   (adaptive icon: background colour + foreground)
 *   mipmap-mdpi ... mipmap-xxxhdpi/ic_launcher.png             (legacy icon, rounded square)
 *                                  ic_launcher_round.png       (legacy icon, circle)
 *                                  ic_launcher_foreground.png  (adaptive foreground, 108dp canvas)
 *   values/ic_launcher_background.xml                          (adaptive background colour)
 * </pre>
 * The manifest then points to {@code @mipmap/ic_launcher} and {@code @mipmap/ic_launcher_round}.
 */
public final class LauncherIconFiles {
    private static final String RES = "app/src/main/res/";
    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    /** Google's launcher sizes in px for mdpi..xxhdpi (48dp baseline x 1, 1.5, 2, 3); xxxhdpi (x4) follows xxhdpi. */
    public static final int[] DEFAULT_SIZES = {48, 72, 96, 144};
    /** Largest launcher bitmap offered: 512 px, the size Google Play asks for its store icon. */
    public static final int MAX_SIZE = 512;
    public static final int MIN_SIZE = 16;
    public static final int STEP = 8;

    private LauncherIconFiles() { }

    /** @param square the chosen picture, already cropped to a square. Returns project-relative path to file bytes. */
    public static Map<String, byte[]> build(Bitmap square) { return build(square, DEFAULT_SIZES); }

    /** @param sizes launcher size in px for mdpi, hdpi, xhdpi and xxhdpi (each clamped to MIN_SIZE..MAX_SIZE); xxxhdpi is derived. */
    public static Map<String, byte[]> build(Bitmap square, int[] sizes) {
        int[] px = new int[5];
        for (int i = 0; i < 5; i++) {
            int base = i < 4 && sizes != null && i < sizes.length ? sizes[i] : (i < 4 ? DEFAULT_SIZES[i] : 0);
            px[i] = i < 4 ? clamp(base) : clamp(Math.round(px[3] * 4f / 3f));
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (square == null || square.getWidth() <= 0 || square.getHeight() <= 0) return files;
        for (int i = 0; i < DENSITIES.length; i++) {
            String dir = RES + "mipmap-" + DENSITIES[i] + "/";
            files.put(dir + "ic_launcher.png", png(legacy(square, px[i], false)));
            files.put(dir + "ic_launcher_round.png", png(legacy(square, px[i], true)));
            files.put(dir + "ic_launcher_foreground.png", png(foreground(square, Math.round(px[i] * 108f / 48f))));
        }
        String adaptive = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <background android:drawable=\"@color/ic_launcher_background\" />\n"
            + "    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\" />\n"
            + "</adaptive-icon>\n";
        files.put(RES + "mipmap-anydpi-v26/ic_launcher.xml", adaptive.getBytes(StandardCharsets.UTF_8));
        files.put(RES + "mipmap-anydpi-v26/ic_launcher_round.xml", adaptive.getBytes(StandardCharsets.UTF_8));
        String background = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<resources>\n"
            + "    <color name=\"ic_launcher_background\">" + backgroundColor(square) + "</color>\n"
            + "</resources>\n";
        files.put(RES + "values/ic_launcher_background.xml", background.getBytes(StandardCharsets.UTF_8));
        return files;
    }

    /** Google Play Console store icon: 512 x 512 px, 32-bit PNG with alpha, under 1024 KB, a full square (Google rounds the corners). */
    public static byte[] playStoreIcon(Bitmap square) {
        if (square == null || square.getWidth() <= 0 || square.getHeight() <= 0) return null;
        Bitmap out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(square, null, new RectF(0, 0, 512, 512), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        byte[] bytes = png(out);
        // A very detailed picture can make the PNG larger than the 1024 KB limit: drop the lowest colour bits until it fits.
        for (int dropped = 1; bytes.length >= 1024 * 1024 && dropped <= 4; dropped++) {
            int[] pixels = new int[512 * 512];
            out.getPixels(pixels, 0, 512, 0, 0, 512, 512);
            int keep = 0xFF & (0xFF << dropped);
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                pixels[i] = (p & 0xFF000000) | (((p >> 16) & keep) << 16) | (((p >> 8) & keep) << 8) | (p & keep);
            }
            out.setPixels(pixels, 0, 512, 0, 0, 512, 512);
            bytes = png(out);
        }
        return bytes;
    }

    private static int clamp(int size) { return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size)); }

    private static Bitmap legacy(Bitmap source, int size, boolean round) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path clip = new Path();
        if (round) clip.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
        else clip.addRoundRect(new RectF(0, 0, size, size), size * 0.12f, size * 0.12f, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawBitmap(source, null, new RectF(0, 0, size, size), paint);
        return out;
    }

    /** 108dp adaptive canvas; the picture fills the 72dp area that is always visible (the rest is the background colour). */
    private static Bitmap foreground(Bitmap source, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float inner = size * 72f / 108f, offset = (size - inner) / 2f;
        canvas.drawBitmap(source, null, new RectF(offset, offset, offset + inner, offset + inner), paint);
        return out;
    }

    private static String backgroundColor(Bitmap source) {
        int pixel = source.getPixel(0, 0);
        if (Color.alpha(pixel) < 255) return "#FFFFFF";
        return String.format(Locale.ROOT, "#%02X%02X%02X", Color.red(pixel), Color.green(pixel), Color.blue(pixel));
    }

    private static byte[] png(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }
}
