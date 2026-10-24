package com.example.studiomobile.workspace;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The editor wallpaper chosen by the user. The picture is copied into app-private storage (so it keeps working when the original
 * file is moved or its permission is gone) and the darkness laid over it is kept in app-private preferences.
 * A wallpaper can also be a short video (at most {@link #MAX_VIDEO_SECONDS} seconds). The video is stored next to the picture and its
 * first frame is stored as the picture, so the poster / preview / lazy loading path is the same for both kinds.
 */
public final class WallpaperSettings {
    public static final int DEFAULT_DIM_PERCENT = 55;
    public static final int MAX_DIM_PERCENT = 85;
    public static final int MAX_VIDEO_SECONDS = 60;
    /** Players report a 60 second clip as e.g. 60.03 s, so a little slack is allowed. */
    private static final long MAX_VIDEO_MS = MAX_VIDEO_SECONDS * 1000L + 500L;
    private static final long MAX_VIDEO_BYTES = 500L * 1024 * 1024;
    private static final int POSTER_MAX_SIDE = 1280;

    /** The chosen video is longer than {@link #MAX_VIDEO_SECONDS} seconds. */
    public static final class TooLongException extends IOException {
        public TooLongException() { super("Video is longer than " + MAX_VIDEO_SECONDS + " seconds"); }
    }

    private static final String PREFERENCES_NAME = "wallpaper_settings";
    private static final String KEY_DIM = "wallpaper_dim_percent";
    private static final String KEY_STAMP = "wallpaper_stamp";
    private static final String FILE_NAME = "wallpaper.jpg";
    private static final String VIDEO_FILE_NAME = "wallpaper.mp4";
    private static final int MAX_SIDE = 2048;
    private static final int NIGHT_RGB = 0x1E1B3F;

    private final SharedPreferences preferences;
    private final File file;
    private final File videoFile;
    private final Context app;

    public WallpaperSettings(Context context) {
        Context app = context.getApplicationContext();
        this.app = app;
        this.videoFile = new File(app.getFilesDir(), VIDEO_FILE_NAME);
        this.preferences = app.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        this.file = new File(app.getFilesDir(), FILE_NAME);
    }

    public boolean isSet() { return file.isFile() && file.length() > 0; }

    /** True when the wallpaper is a live video (the picture file then holds its first frame). */
    public boolean isVideo() { return isSet() && videoFile.isFile() && videoFile.length() > 0; }

    /** The stored video file; only meaningful when {@link #isVideo()} is true. */
    public File videoFile() { return videoFile; }

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
        //noinspection ResultOfMethodCallIgnored
        videoFile.delete();
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
        //noinspection ResultOfMethodCallIgnored
        videoFile.delete();   // a picture replaces a former video wallpaper
        preferences.edit().putLong(KEY_STAMP, System.nanoTime()).apply();
        return decoded;
    }

    /**
     * Stores the picked video (at most {@link #MAX_VIDEO_SECONDS} seconds) as the wallpaper, together with its first frame as the picture.
     * Throws {@link TooLongException} for a longer video. Call off the UI thread.
     */
    public void importVideoFrom(ContentResolver resolver, Uri uri) throws IOException {
        long durationMs;
        Bitmap poster;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(app, uri);
            if (!"yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) throw new IOException("Not a video");
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            durationMs = duration == null ? 0L : Long.parseLong(duration);
            poster = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (poster == null) poster = retriever.getFrameAtTime();
        } catch (RuntimeException e) {
            throw new IOException("Cannot read video", e);
        } finally {
            try { retriever.release(); } catch (Exception ignored) { /* nothing to release */ }
        }
        if (durationMs <= 0 || poster == null) throw new IOException("Cannot read video");
        if (durationMs > MAX_VIDEO_MS) throw new TooLongException();

        int longest = Math.max(poster.getWidth(), poster.getHeight());
        if (longest > POSTER_MAX_SIDE) {
            float scale = POSTER_MAX_SIDE / (float) longest;
            poster = Bitmap.createScaledBitmap(poster, Math.max(1, Math.round(poster.getWidth() * scale)), Math.max(1, Math.round(poster.getHeight() * scale)), true);
        }

        File videoTemp = new File(videoFile.getParentFile(), VIDEO_FILE_NAME + ".tmp");
        File posterTemp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try {
            try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(videoTemp)) {
                if (in == null) throw new IOException("Cannot open video");
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_VIDEO_BYTES) throw new IOException("Video file is too large");
                    out.write(buffer, 0, read);
                }
            }
            try (FileOutputStream out = new FileOutputStream(posterTemp)) {
                if (!poster.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("Cannot save image");
            }
            if (!videoTemp.renameTo(videoFile) || !posterTemp.renameTo(file)) throw new IOException("Cannot save video");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            videoTemp.delete();
            //noinspection ResultOfMethodCallIgnored
            posterTemp.delete();
        }
        preferences.edit().putLong(KEY_STAMP, System.nanoTime()).apply();
    }
}
