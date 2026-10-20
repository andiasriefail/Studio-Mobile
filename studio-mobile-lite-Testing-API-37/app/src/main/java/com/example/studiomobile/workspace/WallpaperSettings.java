package com.example.studiomobile.workspace;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The editor wallpaper chosen by the user. The picture is copied into app-private storage (so it keeps working when the original
 * file is moved or its permission is gone) and the darkness laid over it is kept in app-private preferences.
 */
public final class WallpaperSettings {
    public static final int DEFAULT_DIM_PERCENT = 55;
    public static final int MAX_DIM_PERCENT = 85;

    private static final String PREFERENCES_NAME = "wallpaper_settings";
    private static final String KEY_DIM = "wallpaper_dim_percent";
    private static final String KEY_STAMP = "wallpaper_stamp";
    private static final String FILE_NAME = "wallpaper.jpg";
    private static final int MAX_SIDE = 2048;
    private static final int NIGHT_RGB = 0x1E1B3F;

    private final SharedPreferences preferences;
    private final File file;

    public WallpaperSettings(Context context) {
        Context app = context.getApplicationContext();
        this.preferences = app.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        this.file = new File(app.getFilesDir(), FILE_NAME);
    }

    public boolean isSet() { return file.isFile() && file.length() > 0; }

    /** Changes every time the picture changes, so callers can tell whether a cached bitmap is still current. */
    public long stamp() { return preferences.getLong(KEY_STAMP, 0L); }

    public int dimPercent() { return clampDim(preferences.getInt(KEY_DIM, DEFAULT_DIM_PERCENT)); }

    public void setDimPercent(int percent) { preferences.edit().putInt(KEY_DIM, clampDim(percent)).apply(); }

    static int clampDim(int percent) { return Math.max(0, Math.min(MAX_DIM_PERCENT, percent)); }

    /** The night-blue colour with the alpha that matches the darkness percentage; drawn over the picture so code stays readable. */
    public static int dimColor(int percent) {
        int alpha = Math.round(clampDim(percent) * 255f / 100f);
        return (alpha << 24) | NIGHT_RGB;
    }

    /** Decodes the stored picture, or null when none is set or it cannot be read. */
    public Bitmap load() {
        if (!isSet()) return null;
        try { return BitmapFactory.decodeFile(file.getAbsolutePath()); } catch (Exception | OutOfMemoryError e) { return null; }
    }

    public void clear() {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        preferences.edit().putLong(KEY_STAMP, System.nanoTime()).apply();
    }

    /** Reads the picked image (down-sized, EXIF rotation applied), stores it as the wallpaper and returns it. Call off the UI thread. */
    public Bitmap importFrom(ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open image");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not an image");
        int sample = 1;
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open image");
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) throw new IOException("Cannot decode image");

        Matrix matrix = new Matrix();
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                int orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) matrix.postRotate(90);
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) matrix.postRotate(180);
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) matrix.postRotate(270);
            }
        } catch (Exception ignored) { /* no readable EXIF: keep the picture as decoded */ }
        if (!matrix.isIdentity()) decoded = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);

        // Write next to the final file and rename, so a failed save never leaves a half-written wallpaper behind.
        File temp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            if (!decoded.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("Cannot save image");
        }
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Cannot save image");
        }
        preferences.edit().putLong(KEY_STAMP, System.nanoTime()).apply();
        return decoded;
    }
}
